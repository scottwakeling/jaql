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
package com.ibm.jaql.lang.expr.io;

import com.ibm.jaql.json.type.JsonString;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.expr.core.Expr;
import com.ibm.jaql.lang.expr.function.DefaultBuiltInFunctionDescriptor;
import com.ibm.jaql.lang.util.JaqlUtil;


/**
 * Unregister a key, value pair.
 * 
 * unregisterAdapter(string key) returns key
 */
public class UnregisterAdapterExpr extends Expr
{
  public static class Descriptor extends DefaultBuiltInFunctionDescriptor.Par11
  {
    public Descriptor()
    {
      super("unregisterAdapter", UnregisterAdapterExpr.class);
    }
  }
  
  /**
   * @param exprs
   */
  public UnregisterAdapterExpr(Expr[] exprs) {
    super(exprs);
  }
  
  /* (non-Javadoc)
   * @see com.ibm.jaql.lang.expr.core.Expr#eval(com.ibm.jaql.lang.core.Context)
   */
  @Override
  public JsonString eval(Context context) throws Exception {
    JsonString     key     = JaqlUtil.enforceNonNull((JsonString) exprs[0].eval(context));
    
    JaqlUtil.getAdapterStore().unregister(key);
    return key;
  }
}

