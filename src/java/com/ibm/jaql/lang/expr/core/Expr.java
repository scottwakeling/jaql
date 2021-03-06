/*
 * Copyright (C) IBM Corp. 2008.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.ibm.jaql.lang.expr.core;

import static com.ibm.jaql.json.type.JsonType.ARRAY;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.ibm.jaql.json.schema.Schema;
import com.ibm.jaql.json.schema.SchemaFactory;
import com.ibm.jaql.json.type.BufferedJsonRecord;
import com.ibm.jaql.json.type.JsonArray;
import com.ibm.jaql.json.type.JsonRecord;
import com.ibm.jaql.json.type.JsonUtil;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.json.util.JsonIterator;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.core.SystemNamespace;
import com.ibm.jaql.lang.core.Var;
import com.ibm.jaql.lang.core.VarMap;
import com.ibm.jaql.lang.expr.function.BuiltInFunction;
import com.ibm.jaql.lang.expr.function.BuiltInFunctionDescriptor;
import com.ibm.jaql.lang.expr.function.JsonValueParameters;
import com.ibm.jaql.lang.expr.metadata.MappingTable;
import com.ibm.jaql.lang.expr.top.EnvExpr;
import com.ibm.jaql.lang.expr.top.ExplainExpr;
import com.ibm.jaql.lang.util.JaqlUtil;
import com.ibm.jaql.util.Bool3;
import com.ibm.jaql.util.FastPrintBuffer;
import com.ibm.jaql.util.FastPrinter;

/** Superclass for all JAQL expressions.
 * 
 */
public abstract class Expr
{
  public final static Expr[] NO_EXPRS        = new Expr[0];
  public final static int    UNLIMITED_EXPRS = Integer.MAX_VALUE;

  /** parent expression in which this expression is used, if any, otherwise null */
  protected Expr             parent;					 
  
  /** list of subexpressions (e.g., arguments) */
  protected Expr[]           exprs           = NO_EXPRS;
  // protected Module module;

  /**
   * Every Expr can have annotations in the form of a JsonRecord.
   * They represent operation-specific options / hints. 
   */
  protected BufferedJsonRecord annotations;
  
  public final static MappingTable  EMPTY_MAPPING = new MappingTable();

  
  /**
   * @param exprs
   */
  public Expr(Expr ... exprs)
  {
    this.exprs = exprs;
    for (int i = 0; i < exprs.length; i++)
    {
      if (exprs[i] != null)
      {
        // this is not true during some rewrites
        // assert exprs[i].parent == null;
        if( exprs[i] instanceof InjectAboveExpr )
        {
          exprs[i].replaceInParent(this);
          exprs[i] = exprs[i].exprs[0];
        }
        exprs[i].parent = this;
      }
    }
  }

  public Expr()
  {
    this(NO_EXPRS);
  }
  
  /**
   * @param exprs
   */
  public Expr(List<? extends Expr> exprs)
  {
    this(exprs.toArray(new Expr[exprs.size()]));
  }

  /** Decompiles this expression. The resulting JSON code is written to <tt>exprText</tt>
   * stream and all variables referenced by this expression are added <tt>capturedVars</tt>.
   * 
   * <p> The default implementation only works for built-in functions that do not capture
   * variables (over the ones used in their arguments). It must be overridden for non-functions 
   * and functions that capture variables.
   * 
   * @param exprText
   * @param capturedVars
   * @throws Exception
   */
  public void decompile(FastPrinter exprText, HashSet<Var> capturedVars)
      throws Exception
  {
  	BuiltInFunctionDescriptor d = BuiltInFunction.getDescriptor(this.getClass());
    int i = 0;
    String end = "";
    if( exprs.length > 0 && 
        exprs[0].getSchema().is(ARRAY).always() ) // TODO: this should be if this function expects an array
    {
      exprText.print("( ");
      exprs[0].decompile(exprText, capturedVars);
      exprText.print("\n-> ");
      i++;
      end = " )";
    }
    
    printAnnotations(exprText);
    
    if (SystemNamespace.getInstance().isSystemExpr(this.getClass()))
    {
      exprText.print(SystemNamespace.NAME + "::" + d.getName());
    }
    else
    {
      exprText.print(kw("builtin") + "('" + d.getClass().getName() + "')");
    }
    
    exprText.print("(");
    String sep = "";
    JsonValueParameters p = d.getParameters();
    for( ; i < exprs.length ; i++ )
    {
      if (i<p.numRequiredParameters() || p.hasRepeatingParameter())
      {
        exprText.print(sep);
        exprs[i].decompile(exprText, capturedVars);
        sep = ", ";
      }
      else
      {
        if (exprs[i].isCompileTimeComputable().always() &&
            exprs[i].getEnvExpr() != null ) // FIXME: why were we doing compileTimeEval at runtime?! It bombed here, so I blocked it (ksb)
        {
          if (!JsonUtil.equals(p.defaultOf(i), exprs[i].compileTimeEval()))
          {
            exprText.print(sep);
            exprText.print(p.nameOf(i));
            exprText.print("=");
            exprs[i].decompile(exprText, capturedVars);
            sep = ", ";
          }
        }
        else
        {
          exprText.print(sep);
          exprText.print(p.nameOf(i));
          exprText.print("=");
          exprs[i].decompile(exprText, capturedVars);
          sep = ", ";
        }
      }
    }
    exprText.print(")");
    exprText.print(end);
  }

  /** Quotes the given keyword using # when necessary. This method has to be used when decompiling
   * a keyword. */
  protected String kw(String keyword)
  {
    // currently a keyword will be quoted when there is a global variable of the same name (locals
    // are not a problem because they will be tagged).
    //
    // possible optimization: tag only weak keywords

    if (keyword.contains("#")) throw new IllegalArgumentException();
    EnvExpr top = getEnvExpr();
    if (top == null || top.getEnv().findVar(keyword) != null )
    {
      return '#' + keyword;
    }
    return keyword;
  }
  
  public final JsonRecord getAnnotations()
  {
    return (annotations != null) ? annotations : JsonRecord.EMPTY;
  }
  
  /** 
   * Set the annotations.  The annotations now belong to
   * this Expr and should not be modified externally.
   * @throws Exception 
   */
  public final void setAnnotations(JsonRecord annotations)
  {
    if( annotations == null || annotations.size() == 0 )
    {
      this.annotations = null;
    }
    else
    {
      if( this.annotations == null )
      {
        this.annotations = new BufferedJsonRecord();
      }
      try
      {
        this.annotations.setCopy(annotations);
      }
      catch( Exception e )
      {
        JaqlUtil.rethrow(e);
      }
    }
  }
  
  /**
   * Merge {@code moreAnnos} into our annotations.
   * @throws Exception 
   */
  public final void addAnnotations(JsonRecord moreAnnos)
  {
    if( moreAnnos != null && moreAnnos.size() > 0 )
    {
      if( this.annotations == null )
      {
        setAnnotations(moreAnnos);
      }
      else
      {
        try
        {
          JsonUtil.mergeRecordDeep(annotations, moreAnnos);
        }
        catch( Exception e )
        {
          JaqlUtil.rethrow(e);
        }
      }
    }
  }
  
  /**
   * Conditionally print the annotations record with leading '@' to 
   * the output stream, if we actually have annotations.
   */
  protected void printAnnotations(FastPrinter out) throws IOException
  {
    if( annotations != null && annotations.size() != 0 )
    {
      out.print('@');
      JsonUtil.print(out, annotations);
      out.print(' ');
    }
  }
  
  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString()
  {
    FastPrintBuffer exprText = new FastPrintBuffer();
    HashSet<Var> capturedVars = new HashSet<Var>();
    try
    {
      this.decompile(exprText, capturedVars); // TODO: capturedVars should be optional
      return exprText.toString();
    }
    catch (Exception e)
    {
      return "exception: " + e;
    }
  }

  /** Evaluates this expression and return the result as a value. 
   * 
   * @param context
   * @return
   * @throws Exception
   */
  public abstract JsonValue eval(Context context) throws Exception;

  //  public void write(Context context, TableWriter writer) throws Exception
  //  {
  //    writer.write(eval(context));
  //  }

  /**
   * Evaluates this expression. If it produces an array, this method returns an iterator
   * over the elements of the array. If it produces null, this method returns a null
   * iterator. Otherwise, it throws a cast error.
   * 
   * @param context
   * @return
   * @throws Exception
   */
  public JsonIterator iter(Context context) throws Exception
  {
    JsonValue value = eval(context);
    if (value == null)
    {
      return JsonIterator.NULL;
    }
    JsonArray array = (JsonArray)value; // intentional cast error is possible
    return array.iter();
  }

  // TODO: kill this?
  /**
   * Notify this node and all parents that this subtree was modified.
   */
  protected void subtreeModified()
  {
    //    // Walk up the tree and update any FunctionExpr's
    //    // TODO: clean this up.  See FunctionExpr.
    //    Expr p,c;
    //    for( c = this, p = parent  ; p != null ; c = p, p = c.parent )
    //    {
    //      if( p instanceof FunctionExpr ) 
    //      {
    //        FunctionExpr f = (FunctionExpr)p;
    //        f.fn.setBody(c);
    //      }          
    //    }
  }

  /** Checks whether this expression satisfies the given property. If the property has not been
   * defined for this expression, returns <code>Bool3.UNKNOWN</code>. 
   * 
   * @param prop the property to check for
   * @param deep check the entire tree (<code>true</code>) or just the root (<code>false</code>)? 
   */
  public Bool3 getProperty(ExprProperty prop, boolean deep)
  {
    Map<ExprProperty, Boolean> props = getProperties();
    
    // did it this way to allow easy change of default behavior
    if (deep)
    {
      return getProperty(props, prop, exprs);
    }
    else
    {
      return getProperty(props, prop, null);
    }
  }

  /** Checks whether this expression satisfies the given property. If the property has not been
   * defined for this expression, returns <code>Bool3.UNKNOWN</code>. 
   * 
   * @param prop the property to check for
   * @param children check this expression and the specified children (can be null) 
   */
  protected final Bool3 getProperty(Map<ExprProperty, Boolean> props, ExprProperty prop, Expr[] children)
  {     
    Boolean shallowResult = props.get(prop);
    Bool3 result = shallowResult == null ? Bool3.UNKNOWN : Bool3.valueOf(shallowResult);

    // shallow?
    if (children == null) return result;

    // deep evaluation
    switch (prop.getDistribution())
    {
    case ALL_DESCENDANTS:
      for(int i=0; result!=Bool3.FALSE && i<exprs.length; i++)
      {
        result = result.and(exprs[i].getProperty(prop, true));
       }
      return result;
    case AT_LEAST_ONE_DESCENDANT:
      for(int i=0; result!=Bool3.TRUE && i<exprs.length; i++)
      {
        result = result.or(exprs[i].getProperty(prop, true));
      }
      return result;
    case SHALLOW:
      return result;
    }
    throw new IllegalStateException(); // should never happen
  }
  
  /** Returns the properties of this expression. The default implementation returns
   * {@Link ExprProperty#createUnsafeDefaults()} and must be overridden by subclasses that 
   * deviate from those defaults. */
  public Map<ExprProperty, Boolean> getProperties()
  {
    // TODO: caching?
    Map<ExprProperty, Boolean> result = ExprProperty.createUnsafeDefaults();
    return result;
  }
  
  /** Returns true if it is both safe and efficient to compute this expression at compile time. */
  public final Bool3 isCompileTimeComputable()
  {
    return  getProperty(ExprProperty.ALLOW_COMPILE_TIME_COMPUTATION, true)
        .and(getProperty(ExprProperty.HAS_CAPTURES, true).not())
        .and(getProperty(ExprProperty.HAS_SIDE_EFFECTS, true).not())
        .and(getProperty(ExprProperty.IS_NONDETERMINISTIC, true).not())
        .and(getProperty(ExprProperty.READS_EXTERNAL_DATA, true).not());
  }
  
  /**
   * true iff this expression is compile-time provably going to be
   * evaluated once per evaluation of its parent. If the parent is 
   * evaluated multiple times, this expression might still be 
   * evaluated multiple times.
   * 
   * @return
   */
  public final Bool3 isEvaluatedOnceByParent()
  {
    return parent.evaluatesChildOnce(getChildSlot());
  }
  
//  public boolean needsModuleInformation() {
//  	return false;
//  }
//  
//  public void setModule(Module m) {
//  	module = m;
//  }

  /**
   * 
   * @return
   */
  public Bool3 evaluatesChildOnce(int i)
  {
    return Bool3.UNKNOWN; // TODO: we could (should?) make the default be true because very few operators reval their children
  }

  /** Returns the schema of this expression. The result is determined at compile-time, i.e., no
   * subexpressions are evaluated (though their <code>getSchema()</code> methods might be called).
   * This method may have the side effect of propagating schema information to variables.
   */
  public Schema getSchema()
  {
    if (isCompileTimeComputable().always())
    {
      try
      {
        return SchemaFactory.schemaOf(compileTimeEval());
      } catch (Exception e)
      {
        // ignore
      }
    }
    return SchemaFactory.anySchema();
  }
  
  /**
   * This expression can be applied in parallel per partition of child i.
   */
  public boolean isMappable(int i)
  {
    return false;
  }
  
  /**
   * @return
   */
  public final Expr parent()
  {
    return parent;
  }

  /**
   * @return
   */
  public final int numChildren()
  {
    return exprs.length;
  }

  /**
   * @param i
   * @return
   */
  public final Expr child(int i)
  {
    return exprs[i];
  }

  /**
   * Try not to use this method, and certainly DO NOT MODIFY the array!
   * 
   * @return
   */
  public final Expr[] children()
  {
    return exprs;
  }

  /**
   * Append a child to the end of the list of children
   */
  public void addChild(Expr e)
  {
    if( e instanceof InjectAboveExpr ) // TODO: this really looks like hacking
    {
      this.parent = e.parent;
      e = e.exprs[0];
    }
    e.parent = this;
    Expr[] es = new Expr[exprs.length + 1];
    System.arraycopy(exprs, 0, es, 0, exprs.length);
    es[exprs.length] = e;
    exprs = es;
    subtreeModified();
  }
  
  /** Add a new child before child(slot) */ 
  public void addChildBefore(int slot, Expr e)
  {
    if( e instanceof InjectAboveExpr ) // TODO: this really looks like hacking
    {
      this.parent = e.parent;
      e = e.exprs[0];
    }
    e.parent = this;
    Expr[] es = new Expr[exprs.length + 1];
    System.arraycopy(exprs, 0, es, 0, slot);
    System.arraycopy(exprs, slot, es, slot + 1, exprs.length - slot);
    es[slot] = e;
    exprs = es;
    subtreeModified();
  }

  /** Add more children before child(slot) */ 
  public void addChildrenBefore(int slot, Expr[] children)
  {
    for( Expr e: children )
    {
      assert !(e instanceof InjectAboveExpr);
      e.parent = this;
    }
    Expr[] es = new Expr[exprs.length + children.length];
    System.arraycopy(exprs, 0, es, 0, slot);
    System.arraycopy(children, 0, es, slot, children.length);
    System.arraycopy(exprs, slot, es, slot + children.length, exprs.length - slot);
    exprs = es;
    subtreeModified();
  }

  
  /**
   * @param e
   */
  public void addChildren(ArrayList<? extends Expr> es)
  {
    int n = exprs.length;
    int m = es.size();
    Expr[] exprs2 = new Expr[n + m];
    System.arraycopy(exprs, 0, exprs2, 0, n);
    for( int i = 0 ; i < m ; i++ )
    {
      Expr e = es.get(i);
      if( e instanceof InjectAboveExpr )
      {
        this.parent = e.parent;
        e = e.exprs[0];
      }
      e.parent = this;
      exprs2[n + i] = e;
    }
    exprs = exprs2;
    subtreeModified();
  }

  /** In the parent of this expression, replace this expression by the given expression.
   * @param replaceBy
   */
  public void replaceInParent(Expr replaceBy)
  {
    // This expr is expected to have a parent.  
    // The root expr should never have this method called. 
    Expr[] es = parent.exprs;
    // This expr is expected to be found in its parent's children.
    // This will throw an index exception if the tree is not proper.
    int i = 0;
    while (es[i] != this)
    {
      i++;
    }

    es[i] = replaceBy;
    replaceBy.parent = parent;

    subtreeModified();
    // parent = null;
  }
  
  /** In the parent of this expression, replace this expression by the given expressions.
   * @param replaceBy The list of expressions to add
   * @param offset The start index in replaceBy
   * @param length The number of elements to take from replaceBy
   */
  public void replaceInParent(Expr[] replaceBy, int offset, int length)
  {
    if( length <= 1 )
    {
      if( length == 1 )
      {
        replaceInParent(replaceBy[offset]);
      }
      return;
    }
    
    Expr[] es = parent.exprs;
    // This expr is expected to be found in its parent's children.
    // This will throw an index exception if the tree is not proper.
    int slot = 0;
    while (es[slot] != this)
    {
      slot++;
    }

    es = new Expr[parent.exprs.length + length - 1];

    System.arraycopy(parent.exprs, 0, es, 0, slot);
    System.arraycopy(parent.exprs, slot + 1, es, slot + length, parent.exprs.length - slot - 1);
    
    for(int i = 0 ; i < length ; i++)
    {
      es[slot + i] = replaceBy[offset + i];
      es[slot + i].parent = parent;
    }
    parent.exprs = es;
    
    subtreeModified();
  }

  /** Returns the index of this expression in the parent's list of child expressions.
   * @return
   */
  public int getChildSlot()
  {
    if (parent != null)
    {
      Expr[] es = parent.exprs;
      for (int i = 0; i < es.length; i++)
      {
        if (es[i] == this)
        {
          return i;
        }
      }
    }
    return -1;
  }

  /** Removes the child expression at index in.
   * @param i
   */
  public void removeChild(int i)
  {
    if (i >= 0)
    {
      Expr[] es = new Expr[exprs.length - 1];
      System.arraycopy(exprs, 0, es, 0, i);
      System.arraycopy(exprs, i + 1, es, i, es.length - i);
      exprs = es;
    }
    subtreeModified();
  }

  /** Detaches this expression from it's parent.
   * 
   */
  public void detach()
  {
    if (parent != null)
    {
      int i = getChildSlot();
      parent.removeChild(i);
      parent = null;
    }
  }

  /**
   * Can be used to inject a box above another box like this: Expr e =
   * expr.parent.setChild(expr.getChildSlot(), new FooExpr(expr))
   * 
   * @param i
   * @param e
   * @return
   */
  public Expr setChild(int i, Expr e)
  {
    //    if( exprs[i] != null )
    //    {
    //      exprs[i].parent = null;
    //    }
    if( e instanceof InjectAboveExpr )
    {
      this.parent = e.parent;
      e = e.exprs[0];
    }
    exprs[i] = e;
    e.parent = this;
    subtreeModified();
    return e;
  }
  
  /**
   * Replace all the children expressions.
   * 
   * @param exprs
   */
  public void setChildren(Expr[] exprs)
  {
    for( Expr e: exprs )
    {
      e.parent = this;
    }
    this.exprs = exprs;
    subtreeModified();
  }

  /**
   * Replace all VarExpr(oldVar) with VarExpr(newVar)
   * 
   * @param oldVar
   * @param newVar
   */
  public void replaceVar(Var oldVar, Var newVar)
  {
    for (int i = 0; i < exprs.length; i++)
    {
      exprs[i].replaceVar(oldVar, newVar);
    }
  }
  
  /**
   * Replace all uses of $oldVar with $recVar.fieldName
   * 
   * @param oldVar
   * @param recVar
   * @param fieldName
   * @return
   */
  public Expr replaceVar(Var oldVar, Var recVar, String fieldName)
  {
    for (int i = 0; i < exprs.length; i++)
    {
      exprs[i].replaceVar(oldVar, recVar, fieldName);
    }
    return this;
  }

  /**
   * Return the list of VarExpr's that reference the given variable in this subtree.
   * 
   * @param var
   * @param exprs
   */
  public void getVarUses(Var var, ArrayList<Expr> uses)
  {
    for( Expr e: exprs )
    {
      e.getVarUses(var, uses);
    }
  }

  /**
   * @param varMap
   * @return
   */
  public Expr clone(VarMap varMap)
  {
    Expr[] es = cloneChildren(varMap);
    try
    {
      Constructor<? extends Expr> cons = this.getClass().getConstructor(
          Expr[].class);
      return cons.newInstance(new Object[]{es});
    }
    catch (Exception e)
    {
      throw new UndeclaredThrowableException(e);
    }
  }

  /**
   * @param varMap
   * @return
   */
  public Expr[] cloneChildren(VarMap varMap)
  {
    Expr[] es;
    if (exprs.length == 0)
    {
      es = NO_EXPRS;
    }
    else
    {
      es = new Expr[exprs.length];
      for (int i = 0; i < es.length; i++)
      {
        if (exprs[i] != null)
        {
          es[i] = exprs[i].clone(varMap);
        }
      }
    }
    return es;
  }

  /**
   * returns the distance to the ancestor. getDepth(x,x) == 0 getDepth(x,null)
   * is depth in tree (with root == 1) ancestor must exist above this expr, or a
   * null pointer exception will be raised.
   * 
   * @param ancestor
   * @return
   */
  public int getDepth(Expr ancestor)
  {
    int d = 0;
    for (Expr e = this; e != ancestor; e = e.parent)
    {
      d++;
    }
    return d;
  }

  /**
   * @return
   */
  public HashSet<Var> getCapturedVars()
  {
    // FIXME: this needs to be more efficient... Perhaps I should cache expr properties...
    FastPrintBuffer exprText = new FastPrintBuffer();
    HashSet<Var> capturedVars = new HashSet<Var>();
    try
    {
      decompile(exprText, capturedVars);
    }
    catch (Exception e)
    {
      throw new UndeclaredThrowableException(e);
    }
    return capturedVars;
  }

  /** True iff this Expr tree contains a reference to any variable defined
   * outside this Expr tree, except for any of the ignored variables.
   * If ignoredVars is null, it is treated as empty.
   */
  public boolean hasCaptures(HashSet<Var> ignoredVars)
  {
    // FIXME: this needs to be more efficient... we don't even need the full capture set...
    HashSet<Var> captures = getCapturedVars();
    if( ignoredVars != null )
    {
      captures.removeAll(ignoredVars);
    }
    return ! captures.isEmpty();
  }


  public InjectAboveExpr injectAbove()
  {
    Expr p = this.parent;
    InjectAboveExpr e = new InjectAboveExpr();
    this.replaceInParent(e);
    e.addChild(this);
    e.parent = p;
    return e;
  }

  public final Expr replaceVarUses(Var var, Expr replaceBy, VarMap varMap)
  {
    if(this instanceof VarExpr)
    {
      if( ((VarExpr)this).var == var )
      {
        varMap.clear();
        return replaceBy.clone(varMap);
      }
        return this;
    }
    for( Expr e: exprs )
    {
      Expr e2 = e.replaceVarUses(var, replaceBy, varMap);
      if( e2 != e )
      {
        e.replaceInParent(e2);
      }
    }
    return this;
  }

  /**
   * @param scope
   * @param var
   * @return true iff var is defined by this Expr
   */
  public boolean definesVar(Var var)
  {
    for (Expr e : exprs)
    {
      if (e instanceof BindingExpr)
      {
        BindingExpr b = (BindingExpr) e;
        if (b.var == var || b.var2 == var)
        {
          return true;
        }
      }
    }
    return false;
  }
  
  /**
   * Find the BindingExpr that is an child of an ancestor (inclusive) of this Expr
   * that defines var.
   * 
   * If no such ancestor can be found, null is returned.
   *
   * If var is global, then null is returned. 
   * 
   * @param var
   * @return
   */
  public BindingExpr findVarDef(Var var)
  {
    if( var.isGlobal() )
    {
      // TODO: Global variables should have: Assign(Binding<var>(def)) associated with them
      // This problem was introduced with extern variables because cannot always convert globals into locals.
      assert var.isMutable();
      JsonValue val = var.isDefined() ? var.value() : null;
      return new BindingExpr(BindingExpr.Type.EQ, var, null, new ConstExpr(val));
    }
    for (Expr e = this ; e != null ; e = e.parent())
    {
      for (Expr c : e.children())
      {
        if (c instanceof BindingExpr)
        {
          BindingExpr b = (BindingExpr) c;
          if (b.var == var || b.var2 == var)
          {
            return b;
          }
        }
      }
    }
    return null;
  }

  /**
   * Find the first expr that is a BindingExpr of an ancestor (exclusive) of this Expr
   * that defines a variable with the name varName.  
   * 
   * If no such ancestor can be found, null is returned.
   * 
   * Global variables return null.
   *
   * @param atExpr
   * @param varName
   * @return
   */
  public BindingExpr findVarDef(String varName)
  {
    Expr prev = this;
    for (Expr e = parent() ; e != null ; e = e.parent())
    {
      int i = prev.getChildSlot();
      // look for bindings as direct children before us
      for (i = i - 1; i >= 0; i--)
      {
        Expr c = e.child(i);
        if (c instanceof BindingExpr)
        {
          BindingExpr b = (BindingExpr) c;
          if (varName.equals(b.var.name())
              || (b.var2 != null && varName.equals(b.var2.name())))
          {
            return b;
          }
        }
      }
      prev = e;
    }
    // Var not found...
    return null;
  }
  
  /** Get the root of the Expr tree */
  public Expr getRoot()
  {
    Expr e = this;
    while( e.parent != null )
    {
      e = e.parent;
    }
    return e;
  }
  
  /** Returns the environment expression of the tree (usually the root) if existent, otherwise 
   * returns null */
  public EnvExpr getEnvExpr()
  {
    Expr e = this;
    while (e.parent != null && !(e.parent instanceof ExplainExpr))
    {
      assert !(e instanceof EnvExpr);
      e = e.parent;
    }
    if (e instanceof EnvExpr)
    {
      return (EnvExpr)e;
    }
    return null;
  }
  
  /** 
   * Evaluates this expression at compile-time. The default implementation of this method 
   * retrieves the environment stored at the root of the tree ({@link #getEnvExpr()}); if there 
   * is no such environment it will fail. Subclasses may override this behavior when 
   * a context is not needed for evaluation.
   */
  public JsonValue compileTimeEval() throws Exception
  {
    EnvExpr e = getEnvExpr();
    if (e == null)
    {
      throw new IllegalStateException("expression does not have a top expression.  root class="+getRoot().getClass().getName());
    };
    return e.getEnv().eval(this);
  }

//  /**
//   * Return true iff var is defined above this Expr, including globally.
//   * 
//   * @param var
//   * @param scope
//   * @return
//   */
//  protected boolean isExternalVar(Var var)
//  {
//    if( var.isGlobal() )
//    {
//      return true;
//    }
//    Expr def = findVarDef(var);
//    if( def == null )
//    {
//      return false;
//    }
//    return false;
//  }

  /*
   * Compute the mapping table and return it. Most expressions need only to implement this function, i.e., the 
   * one that takes no arguments. 
   */
  public MappingTable getMappingTable()
  {
	  return  EMPTY_MAPPING;
  }

  /*
   * Compute the mapping table for child 'child_id' and return it. Few expressions need to implement this function
   * such as JOIN and GroupBy.
   */
  public MappingTable getMappingTable(int child_id)
  {
	  return  EMPTY_MAPPING;
  }

}
