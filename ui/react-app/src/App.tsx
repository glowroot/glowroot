function App() {
  const layout = (window as unknown as Record<string, unknown>).layout as
    | { central: boolean; glowrootVersion: string }
    | undefined

  return (
    <div style={{ fontFamily: 'sans-serif', padding: '2rem' }}>
      <h1>Glowroot Modern UI</h1>
      <p>Hello from the modern React UI!</p>
      {layout ? (
        <p>
          Connected to Glowroot {layout.central ? 'Central' : 'Embedded'}{' '}
          version {layout.glowrootVersion}
        </p>
      ) : (
        <p>Layout not available (running in dev mode without backend?)</p>
      )}
      <p>
        <a href="/">&larr; Back to classic UI</a>
      </p>
    </div>
  )
}

export default App
