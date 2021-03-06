/*
 * Copyright (C) IBM Corp. 2009.
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
package com.ibm.jaql.lang.expr.agg;

import com.ibm.jaql.json.schema.SchemaFactory;
import com.ibm.jaql.json.type.JsonUtil;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.expr.core.Expr;
import com.ibm.jaql.lang.expr.function.DefaultBuiltInFunctionDescriptor;
import com.ibm.jaql.lang.expr.function.Function;
import com.ibm.jaql.lang.expr.function.JsonValueParameter;
import com.ibm.jaql.lang.expr.function.JsonValueParameters;

/**
 * 
 */
public final class ArgMaxAgg extends AlgebraicAggregate
{
  private boolean noMax;
  private JsonValue max;
  private JsonValue arg;
  private Function keyFn;
  private JsonValue[] fnArgs = new JsonValue[1];
  private Context context;
  
  public static class Descriptor extends DefaultBuiltInFunctionDescriptor
  {
    public Descriptor()
    {
      super(
          "argmax",
          ArgMaxAgg.class,
          new JsonValueParameters(
              new JsonValueParameter("a", SchemaFactory.arrayOrNullSchema()),
              new JsonValueParameter("f", SchemaFactory.functionSchema())),
            SchemaFactory.anySchema());
    }
  }
  
  
  /**
   * @param exprs 
   */
  public ArgMaxAgg(Expr[] exprs)
  {
    super(exprs);
  }

  @Override
  public void init(Context context) throws Exception
  {
    this.context = context;
    noMax = true;
    keyFn = (Function)exprs[1].eval(context);
  }

  @Override
  public void accumulate(JsonValue value) throws Exception
  {
    if( value != null )
    {
      fnArgs[0] = value;
      keyFn.setArguments(fnArgs);
      JsonValue key = keyFn.eval(context);
      if( key != null )
      {
        if( noMax || JsonUtil.compare(key, max) > 0 )
        {
          noMax = false;
          max = key.getCopy(max);
          arg = value.getCopy(arg);
        }
      }
    }
  }

  @Override
  public JsonValue getPartial() throws Exception
  {
    return noMax ? null : arg;
  }

  @Override
  public void combine(JsonValue value) throws Exception
  {
    accumulate(value);
  }

  @Override
  public JsonValue getFinal() throws Exception
  {
    return noMax ? null : arg;
  }
}
