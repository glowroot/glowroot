var SqlPrettyPrinter = {
  keywords: {groupBy: 'GROUP BY',
             having: 'HAVING',
             orderBy: 'ORDER BY'},
  strFunctions: {
    toUpper: function(s) {
      return s.toUpperCase()
    },
    toLower: function(s) {
      return s.toLowerCase()
    },
    toTitle: function(s) {
      return s.toLowerCase().replace(/((^)|(\s+)|(\.)|_)[a-zA-Z0-9]/g,
      	                             function(t) { return t.toUpperCase() })
    },
    toEqual: function(s) {
      return s
    }
  },
  format: function(query, settings) {
    var ast = null
    try {
      ast = parser.parse(query)
    } catch(e) {
      return e
    }
    
    settings = settings || {}

    var driver = {
      settings: settings,
      buffer: '',
      prevChar: '',
      linePositions: [],
      appendToBuffer: function(s) {
          this.buffer = this.buffer + s
          SqlPrettyPrinter.buffer = this.buffer
      },
      write: function(st, suppressPrepend) {
        var prepend = ' '
        if (st == ',' || st == '' || st == ')' || st == 'SELECT') prepend = ''

        if (this.prevChar =='(') prepend = ''

        if (suppressPrepend) prepend = ''

        this.appendToBuffer(prepend + st)
        this.prevChar = st
      },
      writeUsingSettingsCase: function(st, kase) {
        var caseSetting = this.settings[kase + 'Case']
      	
        if (caseSetting) this.write(SqlPrettyPrinter.strFunctions[caseSetting](st))
      	else this.write(st)
      },
      curLineLength: function() {
        return this.buffer.length - Math.max(0, this.buffer.lastIndexOf('\n') + 1)
      },
      lastLinePositionElem: function() {
        return this.linePositions[this.linePositions.length - 1]
      },
      openParen: function(suppressPrepend) {
        this.write('(', suppressPrepend)
      },
      closeParen: function() {
        this.write(')')
      },
      writeKeyword: function(st) {
        this.write(st.toUpperCase())
      },
      writeIndent: function() {
        this.appendToBuffer('    ')
      },
      wrapToRight: function() {
        this.appendToBuffer('\n')
        var elem = this.lastLinePositionElem()

        for (var i = 0; i < (elem.margin - 1); i++) this.appendToBuffer(' ')
      },
      writeLeftKeyword: function(st) {
        var beforeSpace = '\n'
        var cntWhite
        if (st.toUpperCase() == 'SELECT') {
          beforeSpace = ''
          cntWhite = this.lastLinePositionElem().leftSize - st.length
        } else {
          cntWhite = this.lastLinePositionElem().margin - st.length - 1
        }
         
        this.appendToBuffer(beforeSpace)
        for (var i = 0; i < cntWhite; i++) this.appendToBuffer(' ')
        this.appendToBuffer(st.toUpperCase())
        this.prevChar = st
      },
      saveCurrentPos: function(prefixSize) {
        this.linePositions.push({leftSize: prefixSize, margin:this.curLineLength() + prefixSize + 1})
      },
      restoreCurrentPos: function() {
        this.linePositions.pop()
      }
    }
    
    this.formatSelect(ast.value, driver)

    return SqlPrettyPrinter.buffer
  },
  formatSelect: function(node, driver) {
    this.formatExpressionPlus(node, driver)
  },
  formatSelectItem: function(node, driver) {
    var leftSize = 6
    for (var keyword in SqlPrettyPrinter.keywords) {
      if (node[keyword]) leftSize = Math.max(leftSize, SqlPrettyPrinter.keywords[keyword].length)
    }
    
    driver.saveCurrentPos(leftSize)
    driver.writeLeftKeyword('SELECT')

    if (node.distinct) {
        driver.writeKeyword('DISTINCT');
    }
    if (node.top) {
        driver.writeKeyword('TOP');
        driver.write(node.top);
    }
    for (var i = 0; i < node.columns.length; i++) {
      this.formatColumn(node.columns[i], driver)
      if (node.columns.length > 1 && i != (node.columns.length - 1)) {
        driver.write(',')
        driver.wrapToRight()
      }
    }
    if (node.from.length) {
      driver.writeLeftKeyword('FROM')
      for (var i = 0; i < node.from.length; i++) {
        this.formatFrom(node.from[i], driver)
        if (node.from.length > 1 && i != (node.from.length - 1)) {
          driver.write(',')
          driver.wrapToRight()
        }            
      }
    }
    if (node.where) this.formatWhere(node.where, driver)
    if (node.groupBy) this.formatGroupBy(node.groupBy, driver)
    if (node.having) this.formatHaving(node.having, driver)
    if (node.orderBy) this.formatOrderBy(node.orderBy, driver)
    if (node.limit) {
      driver.writeLeftKeyword('LIMIT');
      driver.write(node.limit);
    }
    if (node.queryHints) this.formatQueryHints(node.queryHints, driver)
    
    driver.restoreCurrentPos()
  },
  formatColumn: function(node, driver) {
    if (typeof node.value == 'string') {
      driver.write(node.value)
    } else {
      this.formatExpression(node.value, driver)
    }
    this.formatAlias(node, driver)
  },
  formatAlias: function(node, driver) {
    if (node.alias) {
      if (node.alias.includeAs) driver.writeKeyword('AS')
      driver.write(node.alias.value)
    }
  },
  formatAndChain: function(node, driver) {
      for (var i = 0; i < node.length; i++) {
      	if (i != 0) driver.writeLeftKeyword('AND')
      	this.formatCondition(node[i], driver)
      }
  },
  formatExpressionPlus: function(node, driver) {
    driver.saveCurrentPos(0)
    for (var i = 0; i < node.length; i++) {
      if (node[i].nodeType === 'Select') {
        this.formatSelectItem(node[i], driver);
      } else if (node[i].nodeType === 'SetOperator') {
        driver.wrapToRight()
        driver.writeKeyword(node[i].value)
        driver.wrapToRight()
      } else {
        this.formatExpression(node[i], driver)
      }
    }
    driver.restoreCurrentPos()
  },
  formatExpression: function(node, driver) {
    if (node.nodeType === 'Select') {
      this.formatSelectItem(node, driver);
    } else if (node.nodeType == 'AndCondition') {
      this.formatAndChain(node.value, driver)
    } else if (node.nodeType == 'DistinctFunctionParam') {
      driver.writeKeyword('DISTINCT');
      this.formatExpression(node.value, driver)
    } else {
      /* OrCondition */
      this.formatExpression(node.left, driver)
      driver.writeLeftKeyword('OR')
      this.formatAndChain(node.right, driver)
    }
  },
  formatFrom: function(node, driver) {
    for (var i = 0; i < node.value.length; i++) {
      this.formatTableFrom(node.value[i], driver)
    }
  },
  formatTableFrom: function(node, driver) {
    if (node.nodeType) {
      driver.writeLeftKeyword('')
      driver.write(node.modifier)
      driver.writeKeyword('JOIN')
      this.formatTableFrom(node.value, driver)
      if (node.expression) {
        driver.writeKeyword('ON')
        this.formatExpression(node.expression, driver)
      }
      return
    }
    if (typeof node.exprName == 'string') {
      driver.writeUsingSettingsCase(node.exprName, 'table')
    } else {
      /* Sub select expression */
      driver.openParen()
      this.formatSelect(node.exprName, driver)
      driver.closeParen()
    }
    this.formatAlias(node, driver)
    if (node.tableHints) {
      driver.writeKeyword('WITH')
      driver.openParen()
      for (var i = 0; i < node.tableHints.length; i++) {
        driver.writeKeyword(node.tableHints[i])
        if (i != (node.tableHints.length - 1)) { 
          driver.write(',')
        }
      }
      driver.closeParen()
    }
  },
  formatWhere: function(node, driver) {
    driver.writeLeftKeyword('WHERE')
    this.formatExpression(node, driver)
  },
  formatGroupBy: function(node, driver) {
    driver.writeLeftKeyword('GROUP BY')
    for (var i = 0; i < node.length; i++) {
      this.formatExpression(node[i], driver)
      if (i != (node.length - 1)) { 
        driver.write(',')
        driver.wrapToRight()
      }
    }
  },
  formatHaving: function(node, driver) {
    driver.writeLeftKeyword('HAVING')
    this.formatExpression(node, driver)
  },
  formatOrderBy: function(node, driver) {
    driver.writeLeftKeyword('ORDER BY')
    for (var i = 0; i < node.length; i++) {
      var elem = node[i]
      this.formatExpression(elem.expression, driver)
      driver.writeKeyword(elem.orderByOrder)
      driver.writeKeyword(elem.orderByNulls)
      if (i != (node.length - 1)) {
        driver.write(',')
        driver.wrapToRight()
      }
    }
  },
  formatCondition: function(node, driver) {
    if (node.nodeType == 'Condition') {
      this.formatOperand(node.value, driver)
    } else if (node.nodeType == 'BinaryCondition') {
      this.formatOperand(node.left, driver)
      this.formatRhs(node.right, driver)
    } else if (node.nodeType == 'ExistsCondition') {
      driver.writeKeyword('EXISTS')
      driver.openParen()
      this.formatSelect(node.value, driver)
      driver.closeParen()
    } else if (node.nodeType == 'NotCondition') {
      driver.writeKeyword('NOT')
      this.formatCondition(node.value, driver)
    }
  },
  formatOperand: function(node, driver) {
    if (node.nodeType == 'Term') {
      if (typeof node.value == 'string') {
        driver.writeUsingSettingsCase(node.value, 'identifier')
      } else {
        /* Sub expression */
        driver.openParen()
        this.formatExpressionPlus(node.value, driver);
        driver.closeParen()
      }
    } else if (node.nodeType == 'Operand'
            || node.nodeType == 'Factor'
            || node.nodeType == 'Summand') {
      this.formatOperand(node.left, driver)
      driver.write(node.op)
      this.formatOperand(node.right, driver)
    } else if (node.nodeType == 'FunctionCall') {
      driver.writeUsingSettingsCase(node.name, 'function')
      driver.openParen()
      if (node.args) {
        for (var i = 0; i < node.args.length; i++) {
          var elem = node.args[i]
          if (typeof elem == 'string') {
            /* Element is STAR or QUALIFIED_STAR */
            driver.write(elem)
          } else {
            this.formatExpression(node.args[i], driver)  
          }
          if (i != (node.args.length - 1)) driver.write(',')
        }
      }
      driver.closeParen()
    } else if (node.nodeType == 'Case') {
      driver.saveCurrentPos(4)
      driver.writeKeyword('CASE')
      driver.saveCurrentPos(5)
      for (var i = 0; i < node.clauses.length; i++) {
        if (i == 0) {
          driver.writeKeyword('WHEN')
        } else {
          driver.writeLeftKeyword('WHEN')
        }
        this.formatExpression(node.clauses[i].when, driver)
        driver.writeKeyword('THEN')
        this.formatExpression(node.clauses[i].then, driver)
      }
      if (node.else) {
        driver.writeLeftKeyword('ELSE')
        this.formatExpression(node.else, driver)
      }
      driver.restoreCurrentPos()
      driver.writeLeftKeyword('END')
      driver.restoreCurrentPos()
    } else if (node.nodeType == 'Select') {
      this.formatSelectItem(node.value, driver)
    } else if (node.nodeType == 'Cast') {
      driver.writeKeyword('CAST')
      driver.openParen(true)
      this.formatExpression(node.expression, driver)
      driver.writeKeyword('AS')
      driver.writeKeyword(node.dataType.name)
      if (node.dataType.len !== null) {
        driver.openParen(true)
        driver.writeKeyword(node.dataType.len)
        driver.closeParen()
      }
      driver.closeParen()
    }
  },
  formatRhs: function(node, driver) {
    if (node.nodeType == 'RhsCompare') {
      driver.write(node.op)
      this.formatOperand(node.value, driver)
    } else if (node.nodeType == 'RhsCompareSub') {
      driver.write(node.op)
      driver.writeKeyword(node.kind)
      driver.openParen()
      this.formatSelect(node.value, driver)
      driver.closeParen()
    } else if (node.nodeType == 'RhsIs') {
      driver.writeKeyword('IS')
      if (node.not) driver.writeKeyword('NOT')
      if (node.distinctFrom) {
        driver.writeKeyword('DISTINCT')
        driver.writeKeyword('FROM')
      }
      this.formatOperand(node.value, driver)
    } else if (node.nodeType == 'RhsInSelect') {
      if (node.not) driver.writeKeyword('NOT')
      driver.writeKeyword('IN')
      driver.openParen()
      this.formatSelect(node.value, driver)
      driver.closeParen()
    } else if (node.nodeType == 'RhsInExpressionList') {
      if (node.not) driver.writeKeyword('NOT')
      driver.writeKeyword('IN')
      driver.openParen()
      for (var i = 0; i < node.value.length; i++) {
        this.formatExpression(node.value[i], driver)
        if (i != (node.value.length - 1)) driver.write(',')
      }
      driver.closeParen()
    } else if (node.nodeType == 'RhsBetween') {
      if (node.not) driver.writeKeyword('NOT')
      driver.writeKeyword('BETWEEN')
      this.formatOperand(node.left, driver)
      driver.writeKeyword('AND')
      this.formatOperand(node.right, driver) 
    } else if (node.nodeType == 'RhsLike') {
      if (node.not) driver.writeKeyword('NOT')
      driver.writeKeyword('LIKE')
      this.formatOperand(node.value, driver)
    }
  },
  formatQueryHints: function(node, driver) {
    driver.writeLeftKeyword('OPTION')
    driver.openParen()
    for (var i = 0; i < node.length; i++) {
      var elem = node[i]
      for (var j = 0; j < elem.length; j++) {
        driver.writeUsingSettingsCase(elem[j], 'identifier')
      }
      if (i != (node.length - 1)) {
        driver.write(',')
      }
    }
    driver.closeParen()
  }
}
