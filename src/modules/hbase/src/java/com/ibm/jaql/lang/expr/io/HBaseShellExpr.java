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

import java.lang.reflect.UndeclaredThrowableException;

import com.ibm.jaql.io.hbase.HBaseShell;
import com.ibm.jaql.json.type.JsonString;
import com.ibm.jaql.lang.core.Context;
import com.ibm.jaql.lang.expr.core.Expr;
import com.ibm.jaql.lang.expr.function.DefaultBuiltInFunctionDescriptor;

/**
 * 
 */
public class HBaseShellExpr extends Expr
{
  public static class Descriptor extends DefaultBuiltInFunctionDescriptor.Par11
  {
    public Descriptor()
    {
      super("hbaseShell", HBaseShellExpr.class);
    }
  }
  
  HBaseShell shell;
  {
    try
    {
      shell = new HBaseShell();
    }
    catch (Exception e)
    {
      throw new UndeclaredThrowableException(e);
    }
  }
  /**
   * @param exprs
   */
  public HBaseShellExpr(Expr[] exprs)
  {
    super(exprs);
  }

  /*
   * (non-Javadoc)
   * 
   * @see com.ibm.jaql.lang.expr.core.Expr#eval(com.ibm.jaql.lang.core.Context)
   */
  @Override
  public JsonString eval(Context context) throws Exception
  {
    JsonString cmd = (JsonString) exprs[0].eval(context);
    return new JsonString(shell.doCommand(cmd.toString()));
  }
}
