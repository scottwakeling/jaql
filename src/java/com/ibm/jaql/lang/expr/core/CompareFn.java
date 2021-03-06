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

import com.ibm.jaql.json.schema.Schema;
import com.ibm.jaql.json.schema.SchemaFactory;
import com.ibm.jaql.json.type.JsonLong;
import com.ibm.jaql.json.type.JsonUtil;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.expr.function.DefaultBuiltInFunctionDescriptor;
import com.ibm.jaql.lang.expr.function.JsonValueParameters;

/**
 * 
 */
public class CompareFn extends Expr
{
  public static class Descriptor extends DefaultBuiltInFunctionDescriptor
  {
    public Descriptor()
    {
      super("compare",
            CompareFn.class,
            new JsonValueParameters("x", "y"),
            SchemaFactory.longSchema());
    }
  }
  
  /**
   * @param exprs
   */
  public CompareFn(Expr[] exprs)
  {
    super(exprs);
  }
  
  @Override
  public Schema getSchema()
  {
    return SchemaFactory.longSchema();
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.Expr#eval(com.ibm.jaql.lang.core.Context)
   */
  public JsonValue eval(final Context context) throws Exception
  {
    JsonValue value1 = exprs[0].eval(context);
    JsonValue value2 = exprs[1].eval(context);
    int cmp = JsonUtil.compare(value1, value2);
    if (cmp < 0)
    {
      return JsonLong.MINUS_ONE;
    }
    else if (cmp == 0)
    {
      return JsonLong.ZERO;
    }
    else
    {
      return JsonLong.ONE;
    }
  }
}
