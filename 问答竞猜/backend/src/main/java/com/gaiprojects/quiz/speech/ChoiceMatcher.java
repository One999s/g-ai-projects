package com.gaiprojects.quiz.speech;

import java.text.Normalizer;
import java.util.*;

/** Conservative full-utterance matching. Never consults correct answers or submits a choice. */
public final class ChoiceMatcher {
  static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
  }

  public static Integer suggest(String transcript, List<String> options, Set<Integer> eliminated) {
    if (transcript == null || transcript.length() > 160 || options == null || options.size() != 4)
      return null;
    // A question mark is uncertainty, not disposable answer punctuation (including fullwidth
    // forms).
    if (Normalizer.normalize(transcript, Normalizer.Form.NFKC).indexOf('?') >= 0) return null;
    String full = normalize(transcript);
    if (full.isEmpty()) return null;
    Set<String> forms = new HashSet<>();
    forms.add(full);
    for (String prefix :
        List.of(
            "ichooseoption",
            "ipickoption",
            "myanswerisoption",
            "theanswerisoption",
            "ichoose",
            "ipick",
            "myansweris",
            "theansweris",
            "answer",
            "option",
            "choice",
            "我的答案是",
            "答案是",
            "我选择",
            "我選擇",
            "我选",
            "我選",
            "选择",
            "選擇",
            "选项",
            "選項",
            "选",
            "選"))
      if (full.startsWith(prefix) && full.length() > prefix.length())
        forms.add(full.substring(prefix.length()));
    List<Set<String>> labels =
        List.of(
            Set.of("a", "ay", "第一项", "第一項", "第一个", "第一個", "1", "一"),
            Set.of("b", "bee", "第二项", "第二項", "第二个", "第二個", "2", "二"),
            Set.of("c", "see", "第三项", "第三項", "第三个", "第三個", "3", "三"),
            Set.of("d", "dee", "第四项", "第四項", "第四个", "第四個", "4", "四"));
    Set<Integer> found = new HashSet<>();
    for (int i = 0; i < 4; i++)
      for (String form : forms)
        if (form.equals(normalize(options.get(i))) || labels.get(i).contains(form)) found.add(i);
    if (found.size() != 1) return null;
    int choice = found.iterator().next();
    return eliminated.contains(choice) ? null : choice;
  }
}
