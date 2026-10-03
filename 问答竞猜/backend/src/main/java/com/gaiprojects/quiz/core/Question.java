package com.gaiprojects.quiz.core;
import java.util.List;
/** Server-only question. Never serialize this object into a pre-answer API response. */
public record Question(String id,String locale,String text,List<String> options,int answer,String explanation,long readingMillis) {
 public Question {
  options=List.copyOf(options);
  if(id==null||id.isBlank()||!List.of("en","zh-CN").contains(locale)||text==null||text.isBlank()
    ||options.size()!=4||options.stream().anyMatch(x->x==null||x.isBlank())||options.stream().distinct().count()!=4
    ||answer<0||answer>3||readingMillis<1000||readingMillis>60000||explanation==null||explanation.isBlank())throw new IllegalArgumentException("Invalid question");
 }
}
