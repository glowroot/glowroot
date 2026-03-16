import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useAgent } from '../../contexts/AgentContext'
import { apiGet } from '../../lib/api'
import { Button } from '../../components/ui/button'
import { PageSpinner } from '../../components/shared/Spinner'
import { HttpError } from '../../components/shared/HttpError'

interface MBeanNode {
  objectName?: string
  nodeName: string
  childNodes?: MBeanNode[]
  expanded?: boolean
  attributeMap?: Record<string, unknown>
  loadingAttributes?: boolean
  attrError?: unknown
  version?: number
}

interface FlatEntry {
  node: MBeanNode
  depth: number
}

function flattenTree(nodes: MBeanNode[], depth: number): FlatEntry[] {
  const result: FlatEntry[] = []
  for (const node of nodes) {
    result.push({ node, depth })
    if (node.expanded && node.childNodes) {
      result.push(...flattenTree(node.childNodes, depth + 1))
    }
  }
  return result
}

function collectExpandedObjectNames(nodes: MBeanNode[]): string[] {
  const result: string[] = []
  for (const node of nodes) {
    if (node.expanded && node.objectName) {
      result.push(node.objectName)
    }
    if (node.childNodes) {
      result.push(...collectExpandedObjectNames(node.childNodes))
    }
  }
  return result
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'object') return JSON.stringify(value, null, 4)
  return String(value)
}

export function JvmMbeanTreePage() {
  const { agentId } = useAgent()
  const [searchParams, setSearchParams] = useSearchParams()
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshMessage, setRefreshMessage] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [, setTick] = useState(0)
  const treeRef = useRef<MBeanNode[]>([])
  const mouseDownRef = useRef<{ x: number; y: number } | null>(null)
  const initialExpandedRef = useRef<string[]>(searchParams.getAll('expanded'))

  // Force a re-render without replacing the tree array
  const rerender = useCallback(() => setTick(t => t + 1), [])

  function syncExpandedParams() {
    const expandedNames = collectExpandedObjectNames(treeRef.current)
    const next = new URLSearchParams(searchParams)
    next.delete('expanded')
    for (const name of expandedNames) {
      next.append('expanded', name)
    }
    setSearchParams(next, { replace: true })
  }

  const load = useCallback(async (isRefresh = false) => {
    if (agentId === null) return
    if (isRefresh) {
      setRefreshing(true)
      setRefreshMessage(null)
    } else {
      setLoading(true)
    }
    setError(null)
    try {
      const expanded =
        treeRef.current.length > 0
          ? collectExpandedObjectNames(treeRef.current)
          : initialExpandedRef.current
      const resp = await apiGet<MBeanNode[] | (Record<string, MBeanNode> & { agentNotConnected?: boolean })>(
        '/backend/jvm/mbean-tree',
        {
          'agent-id': agentId,
          expanded: expanded.length > 0 ? expanded : undefined,
        }
      )
      if (!Array.isArray(resp) && resp.agentNotConnected) {
        treeRef.current = []
        setError({ agentNotConnected: true })
        return
      }
      const nodes: MBeanNode[] = Array.isArray(resp)
        ? resp
        : Object.keys(resp)
            .filter(k => k !== 'agentNotConnected')
            .sort()
            .map(k => resp[k])
      treeRef.current = nodes
      rerender()
      if (isRefresh) {
        setRefreshMessage('Refreshed')
      }
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [agentId, rerender])

  useEffect(() => { load(false) }, [load])

  async function toggleNode(node: MBeanNode) {
    node.version = (node.version || 0) + 1
    const v = node.version
    if (node.expanded) {
      // Collapse
      node.expanded = false
      delete node.attributeMap
      delete node.attrError
      node.loadingAttributes = false
      if (node.objectName) {
        syncExpandedParams()
      }
      rerender()
      return
    }

    // Expand
    node.expanded = true
    delete node.attrError

    // Leaf node with objectName and no children: load attributes
    if (node.objectName && (!node.childNodes || node.childNodes.length === 0)) {
      syncExpandedParams()
      if (!node.attributeMap) {
        node.loadingAttributes = true
        rerender()
        try {
          const attrs = await apiGet<Record<string, unknown>>('/backend/jvm/mbean-attribute-map', {
            'agent-id': agentId!,
            'object-name': node.objectName,
          })
          if (node.version !== v) {
            // Interrupted by collapse before response arrived
            return
          }
          node.attributeMap = attrs
        } catch (e) {
          if (node.version !== v) return
          node.attrError = e
        } finally {
          if (node.version === v) {
            node.loadingAttributes = false
            rerender()
          }
        }
        return
      }
    }

    rerender()
  }

  if (loading && treeRef.current.length === 0) return <PageSpinner />
  if (error && (error as { agentNotConnected?: boolean }).agentNotConnected) {
    return (
      <div className="rounded-md bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
        This feature is only available when the agent is running and connected
      </div>
    )
  }
  if (error && treeRef.current.length === 0) return <HttpError error={error} />

  const flatNodes = flattenTree(treeRef.current, 0)

  return (
    <div>
      <div className="mb-4 flex items-center gap-2">
        <Button variant="outline" size="sm" loading={refreshing} onClick={() => load(true)}>
          Refresh
        </Button>
        {refreshMessage && <span className="text-sm text-green-600">{refreshMessage}</span>}
      </div>
      {error != null && <div className="mb-4"><HttpError error={error} /></div>}
      <div className="rounded-lg border bg-white overflow-auto max-h-[80vh]">
        {flatNodes.map(({ node, depth }, i) => (
          <div key={node.objectName || `${depth}-${node.nodeName}-${i}`}>
            <div
              className="flex items-center px-4 py-1 text-sm hover:bg-gray-50 cursor-pointer border-b border-gray-50"
              style={{ paddingLeft: `${16 + depth * 24}px` }}
              onMouseDown={(e) => { mouseDownRef.current = { x: e.pageX, y: e.pageY } }}
              onClick={(e) => {
                const down = mouseDownRef.current
                mouseDownRef.current = null
                if (down && (Math.abs(e.pageX - down.x) > 5 || Math.abs(e.pageY - down.y) > 5)) {
                  return
                }
                toggleNode(node)
              }}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => { if (e.key === 'Enter') toggleNode(node) }}
            >
              <span className="w-4 mr-1 text-gray-400 text-xs">
                {(node.childNodes && node.childNodes.length > 0) || node.objectName
                  ? node.expanded ? '\u25BC' : '\u25B6'
                  : ''}
              </span>
              <span className="font-mono text-xs">{node.nodeName}</span>
            </div>
            {/* Loading indicator for attributes */}
            {node.expanded && node.loadingAttributes && (
              <div
                className="py-1 text-xs text-gray-500"
                style={{ paddingLeft: `${40 + depth * 24}px` }}
              >
                Loading...
              </div>
            )}
            {node.expanded && node.attrError != null && (
              <div
                className="py-1 pr-4"
                style={{ paddingLeft: `${40 + depth * 24}px` }}
              >
                <HttpError error={node.attrError} />
              </div>
            )}
            {/* Show attributes if expanded leaf */}
            {node.expanded && node.attributeMap && (
              <div
                className="bg-gray-50 border-b border-gray-100 py-1 pr-4"
                style={{ paddingLeft: `${40 + depth * 24}px` }}
              >
                {Object.entries(node.attributeMap).map(([key, value]) => (
                  <div key={key} className="py-0.5 text-xs">
                    <span className="font-medium text-gray-700">{key}: </span>
                    <span className="text-gray-600 break-all">
                      {typeof value === 'object' && value !== null ? (
                        <pre className="inline whitespace-pre-wrap">{formatValue(value)}</pre>
                      ) : (
                        formatValue(value)
                      )}
                    </span>
                  </div>
                ))}
                {node.objectName && !('ObjectName' in node.attributeMap) && (
                  <div className="py-0.5 text-xs">
                    <span className="font-medium text-gray-700">
                      <em>ObjectName</em>:{' '}
                    </span>
                    <span className="text-gray-600 break-all">{node.objectName}</span>
                  </div>
                )}
              </div>
            )}
          </div>
        ))}
        {flatNodes.length === 0 && (
          <p className="px-4 py-3 text-sm text-gray-500">No MBeans found.</p>
        )}
      </div>
    </div>
  )
}
