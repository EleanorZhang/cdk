/*
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.morphline.stdlib;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.AbstractCommand;
import com.cloudera.cdk.morphline.base.Validator;
import com.cloudera.cdk.morphline.shaded.com.google.code.regexp.GroupInfo;
import com.cloudera.cdk.morphline.shaded.com.google.code.regexp.Matcher;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * The Grok command uses regular expression pattern matching to extract structured fields from
 * unstructured log data.
 * <p>
 * It is perfect for syslog logs, apache and other webserver logs, mysql logs, and in general, any
 * log format that is generally written for humans and not computer consumption.
 */
public final class GrokBuilder implements CommandBuilder {

  /*
   * Uses a shaded version of com.google.code.regexp-0.1.9 to minimize potential dependency issues.
   * See https://github.com/tony19/named-regexp
   */
  
  @Override
  public Collection<String> getNames() {
    return Collections.singletonList("grok");
  }
  
  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new Grok(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private static final class Grok extends AbstractCommand {

    private final Map<String, Matcher> regexes = new HashMap();
    private final boolean extract;
    private final boolean extractInPlace;
    private final NumRequiredMatches numRequiredMatches;
    private final boolean findSubstrings;
    private final boolean addEmptyStrings;
    private final String firstKey; // cached value

    public Grok(Config config, Command parent, Command child, MorphlineContext context) {
      super(config, parent, child, context);
      
      GrokDictionaries dict = new GrokDictionaries(config, getConfigs());
      Config exprConfig = getConfigs().getConfig(config, "expressions", ConfigFactory.empty());
      for (Map.Entry<String, Object> entry : exprConfig.root().unwrapped().entrySet()) {
        String expr = entry.getValue().toString();
        this.regexes.put(entry.getKey(), dict.compileExpression(expr).matcher(""));
      }
      this.firstKey = (regexes.size() == 0 ? null : regexes.entrySet().iterator().next().getKey());

      String extractStr = getConfigs().getString(config, "extract", "true");
      this.extractInPlace = extractStr.equals("inplace");
      if (extractInPlace) {
        this.extract = true;
      } else {
        this.extract = getConfigs().getBoolean(config, "extract", true);
      }
      
      this.numRequiredMatches = new Validator<NumRequiredMatches>().validateEnum(
          config,
          getConfigs().getString(config, "numRequiredMatches", NumRequiredMatches.atLeastOnce.toString()),
          NumRequiredMatches.class);
      this.findSubstrings = getConfigs().getBoolean(config, "findSubstrings", false);
      this.addEmptyStrings = getConfigs().getBoolean(config, "addEmptyStrings", false);
      validateArguments();
    }
    
    @Override
    protected boolean doProcess(Record inputRecord) {
      Record outputRecord;
      outputRecord = ((extractInPlace || !extract) ? inputRecord : inputRecord.copy());
      if (extractInPlace) {
        // Ensure that we mutate the record inplace only if *all* expressions match.
        // To ensure this we potentially run doMatch() twice: the first time to check, the second
        // time to mutate
        boolean isFast;
        if (regexes.size() == 0) {
          isFast = true;
        } else if (regexes.size() > 1) {
          isFast = false;
        } else if (numRequiredMatches == NumRequiredMatches.atLeastOnce) {
          isFast = true;
        } else { // all or once
          assert regexes.size() == 1;
          assert firstKey != null;
          isFast = (inputRecord.get(firstKey).size() <= 1);
        }
        
        if (!isFast) {
          if (!doMatch(inputRecord, outputRecord, false)) {
            return false;
          }
        } else {
          ; // no need to do anything
          // This is a performance enhancement for some cases with a single expression:
          // By the time we find a regex match we know that the whole command will succeed,
          // so there's really no need to run doMatch() twice.
        }
      }
      if (!doMatch(inputRecord, outputRecord, extract)) {
        return false;
      }
      
      // pass record to next command in chain:
      return super.doProcess(outputRecord);
    }

    private boolean doMatch(Record inputRecord, Record outputRecord, boolean doExtract) {
      for (Map.Entry<String, Matcher> regexEntry : regexes.entrySet()) {
        Matcher matcher = regexEntry.getValue();
        List values = inputRecord.get(regexEntry.getKey());
        int todo = values.size();
        int minMatches = 1;
        int maxMatches = Integer.MAX_VALUE;
        switch (numRequiredMatches) {
          case once : { 
            maxMatches = 1;
            break;
          }
          case all : { 
            minMatches = todo;
            break;
          }
          default: {
            break;
          }
        }        
        int numMatches = 0;
        for (Object value : values) {
          matcher.reset(value.toString());
          if (!findSubstrings) {
            if (matcher.matches()) {
              numMatches++;
              if (numMatches > maxMatches) {
                return false;
              }
              extract(outputRecord, matcher, doExtract);
            }
          } else {
            int previousNumMatches = numMatches;
            while (matcher.find()) {
              if (numMatches == previousNumMatches) {
                numMatches++;
                if (numMatches > maxMatches) {
                  return false;
                }
                if (!doExtract && numMatches >= minMatches && maxMatches == Integer.MAX_VALUE) {
                  break; // fast path
                }
              }
              extract(outputRecord, matcher, doExtract);
            }
          }
          todo--;
          if (!doExtract && numMatches >= minMatches && maxMatches == Integer.MAX_VALUE) {
            break; // fast path
          }
        }
        if (numMatches + todo < minMatches) {
          return false;          
        }
      }
      return true;
    }

    private void extract(Record outputRecord, Matcher matcher, boolean doExtract) {
      if (doExtract) {
        extractFast(outputRecord, matcher);
      }
    }

    private void extractFast(Record outputRecord, Matcher matcher) {
      for (Map.Entry<String, List<GroupInfo>> entry : matcher.namedPattern().groupInfo().entrySet()) {
        String groupName = entry.getKey();
        List<GroupInfo> list = entry.getValue();
        int idx = list.get(0).groupIndex();
        int group = idx > -1 ? idx + 1 : -1; // TODO cache that number (perf)?
        String value = matcher.group(group);
        if (value != null && (value.length() > 0 || addEmptyStrings)) {
          outputRecord.put(groupName, value);
        }
      }
    }

    
    ///////////////////////////////////////////////////////////////////////////////
    // Nested classes:
    ///////////////////////////////////////////////////////////////////////////////
    private static enum NumRequiredMatches {
      atLeastOnce,
      once,
      all     
    }     

  }
  
}
