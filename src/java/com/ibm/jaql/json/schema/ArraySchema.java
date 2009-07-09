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
package com.ibm.jaql.json.schema;

import com.ibm.jaql.json.type.JsonArray;
import com.ibm.jaql.json.type.JsonLong;
import com.ibm.jaql.json.type.JsonValue;
import com.ibm.jaql.json.util.JsonIterator;
import com.ibm.jaql.util.Bool3;

/** Schema for an array. Has individual schemata for the first k elements and a single schema 
 * for the remaining elements, if any. */
public class ArraySchema extends Schema
{
  // If minRest == maxRest then this is a fixed-length array (head length + rest count)
  // If head == {} and minCount == maxCount == 0, then this is an empty array

  private Schema[]          head;                 // list of schemas for the first elements (never null)
  private Schema            rest;                 // schema of the remaining elements (can be null)
  private JsonLong          minRest;              // min number of values matching rest, >=0, never iff rest==null
  private JsonLong          maxRest;              // max number of items matching rest or null

  
  // -- construction ------------------------------------------------------------------------------
  
  public ArraySchema(Schema[] head, Schema rest, JsonLong minRest, JsonLong maxRest)
  {
    if (head==null) head = new Schema[0];
    
    // assertions to discover internal misusage
    assert rest != null || (minRest==null || minRest==JsonLong.ZERO);
    assert rest != null || (maxRest==null || maxRest==JsonLong.ZERO);
    
    // check arguments
    if (!SchemaUtil.checkInterval(minRest, maxRest, JsonLong.ZERO, JsonLong.ZERO))
    {
      throw new IllegalArgumentException("array repetition out of bounds: " + minRest + " " + maxRest);
    }
    
    // init
    this.head = head;
    this.rest = rest;
    if (rest != null)
    {
      this.minRest = minRest == null ? JsonLong.ZERO : minRest;
      this.maxRest = maxRest;
    }
    
    // move rest to head if occuring precisely once
    if (this.rest!= null && JsonValue.equals(this.minRest, JsonLong.ONE) 
        && JsonValue.equals(this.maxRest, JsonLong.ONE))
    {
      Schema[] newHead = new Schema[this.head.length+1];
      System.arraycopy(this.head, 0, newHead, 0, this.head.length);
      newHead[this.head.length] = rest;
      this.head=newHead;
      this.rest = null;
      this.minRest = this.maxRest = null;
    }
  }
  
  public ArraySchema(Schema schema, JsonLong minCount, JsonLong maxCount)
  {
    this(new Schema[0], schema, minCount, maxCount);
  }
  
  public ArraySchema(Schema schema)
  {
    this(new Schema[] { schema });
  }
  
  /**
   * 
   */
  public ArraySchema(Schema[] schemata)
  {
    this(schemata, null, JsonLong.ZERO, JsonLong.ZERO);
  }

  /** Matches any array */
  ArraySchema()
  {
    this(new Schema[0], SchemaFactory.anyOrNullSchema(), null, null); 
  }
  
  // -- Schema methods ----------------------------------------------------------------------------
  
  @Override
  public SchemaType getSchemaType()
  {
    return SchemaType.ARRAY;
  }
  
  @Override
  public Bool3 isNull()
  {
    return Bool3.FALSE;
  }

  @Override
  public boolean isConstant()
  {
    boolean result = true;
    
    // check head
    for (Schema s : head)
    {
      result = result && s.isConstant();
    }
      
    // check rest
    if (rest==null) return result;
    if (minRest != maxRest) return false; 
    return result && rest.isConstant();
  }

  @Override
  public Bool3 isArrayOrNull()
  {
    return Bool3.TRUE;
  }

  @Override
  public Bool3 isEmptyArrayOrNull()
  {
    if ( head.length==0 )
    {
      if ( rest == null || JsonValue.equals(maxRest, JsonLong.ZERO) )
      {
        return Bool3.TRUE;
      }
      else
      {
        return minRest==null || JsonValue.equals(minRest, JsonLong.ZERO) ? Bool3.UNKNOWN : Bool3.FALSE;
      }
    }
    else
    {
      return Bool3.FALSE;
    }    
  }

  @Override
  public boolean matches(JsonValue value) throws Exception
  {
    if (!(value instanceof JsonArray))
    {
      return false;
    }
    
    // check array head
    JsonArray arr = (JsonArray) value;
    JsonIterator iter = arr.iter();
    for (Schema s : head)
    {
      if (!iter.moveNext() || !s.matches(iter.current()))
      {
        return false;
      }
    }
    
    // check rest
    if (rest==null) return !iter.moveNext();
    assert minRest != null;
    
    // check min rest
    long i = 0;
    for (; i<minRest.value; i++)
    {
      if (!iter.moveNext() || !rest.matches(iter.current()))
      {
        return false;
      }
    }

    // check remaining rest
    assert i==minRest.value;
    for (; ; i++)
    {
      if (!iter.moveNext())
      {
        return true;
      }
      if (maxRest != null && i>=maxRest.value)
      { 
        return false;
      }
      if (!rest.matches(iter.current()))
      {
        return false;
      }
      
    }
  }

  
  // -- getters -----------------------------------------------------------------------------------
  
  public Schema[] getHeadSchemata()
  {
    return head;
  }
  
  public Schema getRestSchema()
  {
    return rest;
  }
  
  public boolean hasRest() 
  {
    return rest != null && (maxRest == null || maxRest.value > 0);    
  }
  
  public boolean isEmpty()
  {
    return head.length == 0 && !hasRest();
  }
  
  public JsonLong getMinRest() 
  {
    return minRest;    
  }
  
  public JsonLong getMaxRest()
  {
    return maxRest;
  }
  
  // -- merge -------------------------------------------------------------------------------------

  @Override
  protected Schema merge(Schema other)
  {
    if (other instanceof ArraySchema)
    {
      ArraySchema o = (ArraySchema)other;
      
      // keep empty arrays separate
      if ((other.isEmptyArray().always() && this.isEmptyArray().never()) ||
          (this.isEmptyArray().always() && other.isEmptyArray().never()))
      {
        return null; 
      }
      
      // determine number of first elements with explicit schema
      long minLength = Math.min(this.head.length + (this.minRest!=null ? this.minRest.value : 0),
          o.head.length + (o.minRest!=null ? o.minRest.value : 0));
      int newHeadLength = Math.max(this.head.length, o.head.length);
      if (newHeadLength > minLength) newHeadLength = (int)minLength;
      
      // derive schemata of those first elements
      Schema[] newHead = new Schema[newHeadLength];
      for (int i=0; i<newHeadLength; i++)
      {
        Schema fromMe = i<this.head.length ? this.head[i] : this.rest;
        Schema fromOther = i<o.head.length ? o.head[i] : o.rest;
        if (fromMe == null)
        {
          assert fromOther != null; // because head does not contain nulls 
          newHead[i] = fromOther;
        }
        else if (fromOther==null)
        {
          newHead[i] = fromMe;
        }
        else
        {
          newHead[i] = SchemaTransformation.merge(fromMe, fromOther);
        }
      }
      
      // determine maximum length of remaining elements
      Long maxRestMe = this.head.length-(long)newHeadLength;
      if (this.rest != null)
      {
        if (this.maxRest == null)
        {
          maxRestMe = null;        
        }
        else
        {
          maxRestMe += this.maxRest.value;
        }      
      }

      Long maxRestOther = o.head.length-(long)newHeadLength;
      if (o.rest != null)
      {
        if (o.maxRest == null)
        {
          maxRestOther = null;        
        }
        else
        {
          maxRestOther += o.maxRest.value;
        }      
      }
      
      JsonLong newMaxRest;
      if (maxRestMe == null || maxRestOther == null)
      {
        newMaxRest = null;
      }
      else
      {
        newMaxRest = new JsonLong(Math.max(maxRestMe, maxRestOther)); 
      }
      
      // if no rest is allowed, we are done
      if (JsonValue.equals(newMaxRest, JsonLong.ZERO))
      {
        return new ArraySchema(newHead);
      }
      
      // there is a rest: determine minRest
      long minRestMe =  Math.max(0, this.head.length-newHeadLength + (this.minRest!=null ? this.minRest.value : 0L));
      long minRestOther = Math.max(0, o.head.length-newHeadLength + (o.minRest!=null ? o.minRest.value : 0L)); 
      JsonLong newMinRest = new JsonLong(Math.min(minRestMe, minRestOther));
      
      assert newMaxRest==null || newMinRest.value<=newMaxRest.value;
      
      // finally, determine schema of rest from remaining heads
      Schema newRest = null;
      for (int i=newHeadLength; i<Math.max(this.head.length, o.head.length); i++)
      {
        if (i < this.head.length)
        {
          newRest = newRest==null ? this.head[i] : SchemaTransformation.merge(newRest, this.head[i]);
        }
        if (i < o.head.length)
        {
          newRest = newRest==null ? o.head[i] : SchemaTransformation.merge(newRest, o.head[i]);
        }
      }
      
      // and from individual rests
      if (this.rest != null)  // can be optimized
      {
        newRest = newRest==null ? this.rest : SchemaTransformation.merge(newRest, this.rest);
      }
      if (o.rest != null)
      {
        newRest = newRest==null ? o.rest : SchemaTransformation.merge(newRest, o.rest);
      }
      
      // done
      return new ArraySchema(newHead, newRest, newMinRest, newMaxRest);
    }
    return null;
  }

  
  // -- introspection -----------------------------------------------------------------------------

  @Override
  public Schema elements()
  {
    if (head.length == 0)
    {
      return rest;
    }
    
    Schema combinedSchema = head[0];
    for (int i=1; i<head.length; i++)
    {
      combinedSchema = SchemaTransformation.merge(combinedSchema, head[i]);
    }
    if (rest != null)
    {
      combinedSchema = SchemaTransformation.merge(combinedSchema, rest);
    }
    return combinedSchema;
  }
  
  @Override
  public Bool3 hasElement(JsonValue which)
  {
    if (which instanceof JsonLong)
    {
      long index = ((JsonLong)which).value;
      if (index < head.length)
      {
        return Bool3.TRUE;
      }
      else
      {
        index -= head.length;
        if (maxRest==null || index < maxRest.value)
        {
          if (minRest!=null && index<minRest.value)
          {
            return Bool3.TRUE;
          }
          return Bool3.UNKNOWN;
        }
        return Bool3.FALSE;
      }
    }
    return Bool3.FALSE;
  }
  
  @Override
  public Schema element(JsonValue which)
  {
    if (which instanceof JsonLong)
    {
      long index = ((JsonLong)which).value;
      if (index < head.length)
      {
        return head[(int)index];
      }
      else 
      {
        index -= head.length;
        if (maxRest==null || index < maxRest.value)
        {
          return rest;
        }
        return null;
      }
    }
    return null; 
  }

  @Override
  public JsonLong minElements()
  {
    return new JsonLong(head.length + (rest!=null && minRest!=null ? minRest.value : 0));
  }

  @Override
  public JsonLong maxElements()
  {
    if (rest != null && maxRest == null) return null;
    return new JsonLong(head.length + (rest!=null ? maxRest.value : 0));
  }  
}