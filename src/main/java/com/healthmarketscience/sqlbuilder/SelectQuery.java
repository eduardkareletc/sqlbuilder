/*
Copyright (c) 2008 Health Market Science, Inc.

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
USA

You can contact Health Market Science at info@healthmarketscience.com
or at the following address:

Health Market Science
2700 Horizon Drive
Suite 200
King of Prussia, PA 19406
*/

package com.healthmarketscience.sqlbuilder;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.healthmarketscience.common.util.AppendableExt;
import com.healthmarketscience.sqlbuilder.dbspec.Column;
import com.healthmarketscience.sqlbuilder.dbspec.Join;
import com.healthmarketscience.sqlbuilder.dbspec.Table;



/**
 * Query which generates a SELECT statement.  Supports arbitrary columns
 * (including "DISTINCT" modifier), "FOR UPDATE" clause, all join types,
 * "WHERE" clause, "GROUP BY" clause, "ORDER BY" clause, and "HAVING" clause.
 *<p>
 * If Columns are used for any referenced columns, and no complicated joins
 * are required, the table list may be left empty and it will be
 * auto-generated in the append call.  Note, that this is not the most
 * efficient method (as this list will not be cached for the future due to
 * mutability constraints on <code>appendTo</code>).
 *
 * @author James Ahlborn
 */
public class SelectQuery extends Query
{
  /**
   * Enum which defines the join types supported in a FROM clause.
   */
  public enum JoinType
  {
    INNER(" INNER JOIN "),
    LEFT_OUTER(" LEFT OUTER JOIN "),
    RIGHT_OUTER(" RIGHT OUTER JOIN "),
    FULL_OUTER(" FULL OUTER JOIN ");

    private String _joinClause;

    private JoinType(String joinClause) {
      _joinClause = joinClause;
    }
    
    @Override
    public String toString() { return _joinClause; }
  }


  private boolean _isDistinct;
  private boolean _forUpdate;
  private SqlObjectList<SqlObject> _columns = SqlObjectList.create();
  private SqlObjectList<SqlObject> _joins = SqlObjectList.create("");
  private List<SqlObject> _fromTables = new LinkedList<SqlObject>();
  private ComboCondition _condition = ComboCondition.and();
  private SqlObjectList<SqlObject> _grouping = SqlObjectList.create();
  private SqlObjectList<SqlObject> _ordering = SqlObjectList.create();
  private ComboCondition _having = ComboCondition.and();

  public SelectQuery() {
    this(false);
  }

  public SelectQuery(boolean isDistinct) {
    _isDistinct = isDistinct;
  }

  /** Returns the columns in this select query. */
  SqlObjectList<SqlObject> getColumns() { return _columns; }

  /** Returns the ordering in this select query. */
  SqlObjectList<SqlObject> getOrdering() { return _ordering; }
  
  /**
   * Returns <code>true</code> iff this select query is using some sort of
   * "*" syntax as a column placeholder.
   * <p>
   * Note, this method is package scoped because it should not be used
   * externally, just by some related query classes for internal validation.
   */
  boolean hasAllColumns()
  {
    for(SqlObject sqlObj : _columns) {
      if((sqlObj instanceof AllTableColumns) || (sqlObj == ALL_SYMBOL)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Handles updating the internal collections with the "from" table in a
   * join.
   */
  private void addJoinFromTable(SqlObject fromTable)
  {
    if(_joins.isEmpty()) {
      // add first from table
      _joins.addObject(fromTable);
    }
    // track all from tables in case the user does validation
    _fromTables.add(fromTable);
  }
  
  /** Iff isDistinct is <code>true</code>, adds the DISTINCT keyword to the
      SELECT clause so that only unique rows are returned */
  public SelectQuery setIsDistinct(boolean isDistinct) {
    _isDistinct = isDistinct;
    return this;
  }

  /** Iff forUpdate is <code>true</code>, adds the FOR UPDATE clause to the
      end of the SELECT clause */
  public SelectQuery setForUpdate(boolean forUpdate) {
    _forUpdate = forUpdate;
    return this;
  }
  
  /**
   * Adds the given columns to the SELECT column list.
   * <p>
   * {@code Object} -&gt; {@code SqlObject} conversions handled by
   * {@link Converter#COLUMN_VALUE_TO_OBJ}.
   */
  public SelectQuery addCustomColumns(Object... columnStrs) {
    _columns.addObjects(Converter.COLUMN_VALUE_TO_OBJ, columnStrs);
    return this;
  }
    
  /** Adds the ALL_SYMBOL to the select column list. */
  public SelectQuery addAllColumns() {
    _columns.addObject(ALL_SYMBOL);
    return this;
  }
    
  /** Adds a <code>"&lt;alias&gt;.*"</code> column to the select column
      list. */
  public SelectQuery addAllTableColumns(Table table) {
    _columns.addObject(new AllTableColumns(table));
    return this;
  }

  /** Adds the given columns to the SELECT column list. */
  public SelectQuery addColumns(Column... columns) {
    return addCustomColumns((Object[])columns);
  }

  /**
   * Adds a table to the FROM clause, should not be used with any
   * <code>add*Join</code> methods
   * <p>
   * {@code Object} -&gt; {@code SqlObject} conversions handled by
   * {@link Converter#toCustomTableDefSqlObject(Object)}.
   */
  public SelectQuery addCustomFromTable(Object tableStr)
  {
    SqlObject tableObj = Converter.toCustomTableDefSqlObject(tableStr);
    if(_joins.isEmpty()) {
      _joins.addObject(tableObj);
    } else {
      _joins.addObject(new JoinTo(tableObj));
    }
    return this;
  }

  /** Adds a table to the FROM clause, should not be used with any
      <code>add*Join</code> methods */
  public SelectQuery addFromTable(Table table)
  {
    return addCustomFromTable(table);
  }

  /**
   * Adds a join of the given type from fromTableStr to toTableStr on
   * joinCond of the given join type.
   * <p>
   * {@code Object} -&gt; {@code SqlObject} conversions handled by
   * {@link Converter#toCustomTableDefSqlObject(Object)}.
   */
  public SelectQuery addCustomJoin(JoinType joinType, Object fromTableStr,
                                   Object toTableStr, Condition joinCond)
  {
    addJoinFromTable(Converter.toCustomTableDefSqlObject(fromTableStr));
    
    // add to table
    _joins.addObject(
        new JoinTo(joinType,
                   Converter.toCustomTableDefSqlObject(toTableStr),
                   joinCond));
    return this;
  }

  /** Adds a join of the given type from fromTable to toTable on joinCond of
      the given join type. */
  public SelectQuery addJoin(JoinType joinType, Table fromTable,
                             Table toTable, Condition joinCond)
  {
    return addCustomJoin(joinType, new TableDefObject(fromTable),
                         new TableDefObject(toTable), joinCond);
  }

  /**
   * Adds a join of the given type from fromTable to toTable with a join
   * condition requiring each column in fromColumns to equal the corresponding
   * column in toColumns.
   */
  public SelectQuery addJoin(JoinType joinType,
                             Table fromTable,
                             Table toTable,
                             List<? extends Column> fromColumns,
                             List<? extends Column> toColumns) {
    addJoinFromTable(new TableDefObject(fromTable));
    
    // add to table
    _joins.addObject(new JoinTo(joinType, new TableDefObject(toTable),
                                fromColumns, toColumns));
    return this;
  }
  
  /**
   * Adds a join of the given type from fromTable to toTable with a join
   * condition requiring fromColumn to equal toColumn.
   */
  public SelectQuery addJoin(JoinType joinType,
                             Table fromTable, Table toTable,
                             Column fromColumn,
                             Column toColumn)
  {
    
    return addJoin(joinType, fromTable, toTable, 
        Collections.singletonList(fromColumn), Collections.singletonList(toColumn));
  }
    
  /** Adds all of the joins of the given join type where each join is from
      join.getFromTable() to join.getToTable() with a join condition
      requiring each column in join.getFromColumns() to equal the
      corresponding column in join.getToColumns(). */
  public SelectQuery addJoins(JoinType joinType, Join... joins) {
    if(joins != null) {
      for(Join join : joins) {
        addJoin(joinType, join.getFromTable(), join.getToTable(),
                join.getFromColumns(), join.getToColumns());
      }
    }
    return this;
  }

  /**
   * Adds the given column with the given direction to the "ORDER BY"
   * clause
   * <p>
   * {@code Object} -&gt; {@code SqlObject} conversions handled by
   * {@link Converter#toCustomColumnSqlObject(Object)}.
   */
  public SelectQuery addCustomOrdering(Object columnStr,
                                       OrderObject.Dir dir) {
    return addCustomOrderings(
        new OrderObject(dir, Converter.toCustomColumnSqlObject(columnStr)));
  }

  /**
   * Adds the given columns to the "ORDER BY" clause
   * <p>
   * {@code Object} -&gt; {@code SqlObject} conversions handled by
   * {@link Converter#CUSTOM_COLUMN_TO_OBJ}.
   */
  public SelectQuery addCustomOrderings(Object... columnStrs) {
    _ordering.addObjects(Converter.CUSTOM_COLUMN_TO_OBJ, columnStrs);
    return this;
  }

  /** Adds the given column with the given direction to the "ORDER BY"
      clause */
  public SelectQuery addOrdering(Column column, OrderObject.Dir dir) {
    return addCustomOrdering(column, dir);
  }

  /** Adds the given columns to the "ORDER BY" clause */
  public SelectQuery addOrderings(Column... columns) {
    return addCustomOrderings((Object[])columns);
  }

  /** Adds the given column index with the given direction to the "ORDER BY"
      clause */
  public SelectQuery addIndexedOrdering(Integer columnIdx,
                                        OrderObject.Dir dir) {
    return addCustomOrdering(columnIdx, dir);
  }

  /** Adds the given column index to the "ORDER BY" clause */
  public SelectQuery addIndexedOrderings(Integer... columnIdxs) {
    return addCustomOrderings((Object[])columnIdxs);
  }

  /**
   * Adds the given columns to the "GROUP BY" clause
   * <p>
   * {@code Object} -&gt; {@code SqlObject} conversions handled by
   * {@link Converter#CUSTOM_COLUMN_TO_OBJ}.
   */
  public SelectQuery addCustomGroupings(Object... columnStrs) {
    _grouping.addObjects(Converter.CUSTOM_COLUMN_TO_OBJ, columnStrs);
    return this;
  }

  /** Adds the given columns to the "GROUP BY" clause */
  public SelectQuery addGroupings(Column... columns) {
    return addCustomGroupings((Object[])columns);
  }    

  /**
   * Allows access to the AND ComboCondition of the where clause to facilitate
   * common condition building code.
   * @return the AND ComboCondition of the WHERE clause for the select query.
   */
  public ComboCondition getWhereClause() {
    return _condition;
  }
  
  /** 
   * Adds a condition to the WHERE clause for the select query (AND'd with any
   * other WHERE conditions).  Note that the WHERE clause will only be
   * generated if some conditions have been added.
   * <p>
   * For convenience purposes, the SelectQuery generates it's own
   * ComboCondition allowing multiple conditions to be AND'd together.  To OR
   * conditions or perform other logic, the ComboCondition must be built and
   * added to the SelectQuery.
   */
  public SelectQuery addCondition(Condition newCondition) {
    _condition.addCondition(newCondition);
    return this;
  }

  /**
   * Allows access to the AND ComboCondition of the having clause to
   * facilitate common condition building code.
   * @return the AND ComboCondition of the HAVING clause for the select query.
   */
  public ComboCondition getHavingClause() {
    return _having;
  }
  
  /** 
   * Adds a condition to the HAVING clause for the select query (AND'd with
   * any other HAVING conditions).  Note that the HAVING clause will only be
   * generated if some conditions have
   been added.
   * <p>
   * For convenience purposes, the SelectQuery generates it's own
   * ComboCondition allowing multiple HAVING conditions to be AND'd together.
   * To OR conditions or perform other logic, the ComboCondition must be built
   * and added to the SelectQuery.
   */
  public SelectQuery addHaving(Condition newCondition) {
    _having.addCondition(newCondition);
    return this;
  }
  
  @Override
  protected void collectSchemaObjects(Collection<Table> tables,
                                  Collection<Column> columns) {
    _joins.collectSchemaObjects(tables, columns);
    _columns.collectSchemaObjects(tables, columns);
    _condition.collectSchemaObjects(tables, columns);
    _grouping.collectSchemaObjects(tables, columns);
    _ordering.collectSchemaObjects(tables, columns);
    _having.collectSchemaObjects(tables, columns);
  }

  @Override
  public SelectQuery validate()
    throws ValidationException
  {
    // if we have joins, check the tables, otherwise, the join tables will
    // be auto generated during output (so don't bother checking them)
    boolean checkTables = !(_joins.isEmpty());

    // validate super class
    Set<Table> tables = new HashSet<Table>();
    Set<Column> columns = new HashSet<Column>();
    Set<Table> columnTables = new HashSet<Table>();
    super.validate(checkTables, tables, columns, columnTables);

    // if we don't have any tables, but we don't have any Columns either,
    // that's a problem (because we can't infer the tables in this case)
    if((!checkTables) && (columns.isEmpty())) {
      // we must have some tables in this case
      throw new ValidationException("No tables given in select");
    }

    // note, if _fromTables is empty, then all the referenced tables are in
    // the _joins collection (using add*FromTable() methods), and no
    // extended validation needs to be done
    if(checkTables && !_fromTables.isEmpty()) {
      
      // verify that all the "from" tables not added to the _joins list
      // actually show up where they should.
      // 
      // Given (join F0 to T0), (join F1 to T1), (join F2 to T2), ... :
      // Each table F<N> must show up among (F<0> U F<0..N-1> U T<0..N-1>)
      //
      // _joins  = F0, T0, T1, T2 ...
      // _fromTables = F0, F1, F2 ...
      // 
      Set<Table> joinTables = new HashSet<Table>();
      Set<Table> fromTable = new HashSet<Table>();
      Set<Column> joinColumns = new HashSet<Column>();
      Iterator<SqlObject> fromIter = _fromTables.iterator();
      Iterator<SqlObject> toIter = _joins.iterator();
      
      // the first toIter table is actually F0 (see comment above)
      toIter.next().collectSchemaObjects(fromTable, joinColumns);

      while(fromIter.hasNext() && toIter.hasNext()) {
        
        // add the previous from table to the common from/to tables collection
        joinTables.addAll(fromTable);

        // grab the next from table
        fromTable.clear();
        fromIter.next().collectSchemaObjects(fromTable, joinColumns);

        // verify that it exists among the previous from/to tables
        if(!joinTables.containsAll(fromTable)) {
          throw new ValidationException(
              "Table " + fromTable +
              " used in join is not given among the previous tables: " +
              joinTables);
        }

        // grab the next to table
        toIter.next().collectSchemaObjects(joinTables, joinColumns);
      }
      if(fromIter.hasNext() || toIter.hasNext()) {
        // mismatched tables?
        throw new ValidationException("Mismatched tables in joins");
      }
      
    }
    
    validateOrdering(_columns.size(), _ordering, hasAllColumns());
    
    return this;
  }

  /**
   * Checks any indexed ordering values for validity using a variety of
   * criteria.
   * @param numColumns number of column objects in the query
   * @param ordering the ordering objects for the query
   * @param ignoreColumnCount whether the given numColumns is meaningful
   *                          (i.e. does not include a '*' character)
   */
  protected static void validateOrdering(int numColumns,
                                         SqlObjectList<SqlObject> ordering,
                                         boolean ignoreColumnCount)
    throws ValidationException
  {
    // if we should ignore the column count, just set it to max integer (we
    // can still check other things)
    if(ignoreColumnCount) {
      numColumns = Integer.MAX_VALUE;
    }
    
    // check that any ordering indexes are valid
    for(SqlObject orderObj : ordering) {
      if(orderObj instanceof OrderObject) {
        orderObj = ((OrderObject)orderObj).getObject();
      }
      if(orderObj instanceof NumberValueObject) {
        NumberValueObject numObj = (NumberValueObject)orderObj;
        if(numObj.isFloatingPoint()) {
          throw new ValidationException(
              "Ordering indexes must be integer values, given: " + numObj);
        }
        // note that index is 1 based
        long idx = numObj.getValue().longValue();
        if((idx < 1) || (idx > numColumns)) {
          throw new ValidationException(
              "Ordering index out of range, given: " + idx + ", range: 1 to " +
              numColumns);
        }
      }
    }
  }

  @Override
  protected void appendTo(AppendableExt app, SqlContext newContext)
    throws IOException
  {
    newContext.setUseTableAliases(true);
    
    // append basic select
    app.append("SELECT ");

    if(_isDistinct) {
      app.append("DISTINCT ");
    }
      
    app.append(_columns).append(" FROM ");

    SqlObjectList<SqlObject> joins = _joins;
    if(joins.isEmpty()) {
        
      // auto generate the join tables from all the referenced columns
      joins = SqlObjectList.create();

      // note, we don't cache this collection because we don't want the
      // appendTo() method to mutate object state.
      // note, we use LinkedHashSet to preserve the order that the tables were
      // referenced (for lack of a better choice of ordering)
      Set<Table> tables = new HashSet<Table>();
      Set<Column> columns = new LinkedHashSet<Column>();
      collectSchemaObjects(tables, columns);
        
      Set<Table> columnTables = new LinkedHashSet<Table>();
      getColumnTables(columns, columnTables);

      for(Table table : columnTables) {
        joins.addObject(new TableDefObject(table));
      }
    }

    // append the joins
    app.append(joins);
      
    if(!_condition.isEmpty()) {
      // append "where" condition(s)
      app.append(" WHERE ").append(_condition);
    }

    if(!_grouping.isEmpty()) {
      // append grouping clause
      app.append(" GROUP BY ").append(_grouping);
      if (!_having.isEmpty()) {
        // append having clause
        app.append(" HAVING ").append(_having);
      }
    }
    
    if(!_ordering.isEmpty()) {
      // append ordering clause
      app.append(" ORDER BY ").append(_ordering);
    }

    if(_forUpdate) {
      app.append(" FOR UPDATE");
    }
  }
  
  
  /**
   * Outputs the right side of a join clause
   * <code>"&lt;joinType&gt; &lt;toTable&gt; ON &lt;joinCondition&gt;"</code>.
   */
  private static class JoinTo extends SqlObject
  {
    private SqlObject _toTable;
    private JoinType _joinType;
    private Condition _onCondition;

    private JoinTo(SqlObject toTable) {
      this(null, toTable, null);
    }

    private JoinTo(JoinType joinType,
                   SqlObject toTable,
                   List<? extends Column> fromColumns,
                   List<? extends Column> toColumns)
    {
      this(joinType, toTable, ComboCondition.and());
      
      // create join condition
      ComboCondition onCondition = (ComboCondition)_onCondition;
      for(int i = 0; i < fromColumns.size(); ++i) {
        onCondition.addCondition(
          BinaryCondition.equalTo(fromColumns.get(i), toColumns.get(i)));
      }
    }

    private JoinTo(JoinType joinType, SqlObject toTable, Condition onCondition)
    {
      _toTable = toTable;
      _joinType = joinType;
      _onCondition = onCondition;
    }

    @Override
    protected void collectSchemaObjects(Collection<Table> tables,
                                    Collection<Column> columns) {
      _toTable.collectSchemaObjects(tables, columns);
      if(_onCondition != null) {
        _onCondition.collectSchemaObjects(tables, columns);
      }
    }

    @Override
    public void appendTo(AppendableExt app) throws IOException {
      if(_joinType != null) {
        // this is a "complicated" join
        app.append(_joinType).append(_toTable)
          .append(" ON ").append(_onCondition);
      } else {
        // this is a "simple" join
        app.append(", ").append(_toTable);
      }
    }
    
  }

}