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
package com.ibm.jaql.lang.expr.array;

import com.ibm.jaql.json.util.JsonIterator;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.expr.core.Expr;
import com.ibm.jaql.lang.expr.core.IterExpr;
import com.ibm.jaql.lang.expr.function.DefaultBuiltInFunctionDescriptor;

// TODO: introduce macro exprs that always rewrite into something else
/**
 * @jaqlDescription Combines two values to an array. 
 * 
 * Usage:
 * array pair( any , any ); 
 * 
 * The arguments can be any type of date, each of them will be one element of the return array. 
 * 
 * @jaqlExample pair("element1", "element2");
 * [ "element1" , "element2" ]
 * 
 */
public class PairFn extends IterExpr // TODO: rewrite into [A,B]
{
  public static class Descriptor extends DefaultBuiltInFunctionDescriptor.Par22
  {
    public Descriptor()
    {
      super("pair", PairFn.class);
    }
  }
  
  /**
   * [ exprs[0], exprs[1] ]
   * 
   * @param exprs
   */
  public PairFn(Expr[] exprs)
  {
    super(exprs);
  }

  /**
   * @param expr0
   * @param expr1
   */
  public PairFn(Expr expr0, Expr expr1)
  {
    super(new Expr[]{expr0, expr1});
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.IterExpr#iter(com.ibm.jaql.lang.core.Context)
   */
  @Override
  public JsonIterator iter(final Context context) throws Exception
  {
    return new JsonIterator() {
      int index = 0;

      public boolean moveNext() throws Exception
      {
        if (index < exprs.length)
        {
          currentValue = exprs[index].eval(context);
          index++;
          return true;
        }
        return false;
      }
    };
  }
}
