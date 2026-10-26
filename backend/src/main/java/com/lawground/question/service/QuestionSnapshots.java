package com.lawground.question.service;

import com.lawground.question.dto.QuestionResponse;
import java.util.*;

/** Same serialization shape for user traces and golden cases. Labels never belong here. */
public final class QuestionSnapshots {
    private QuestionSnapshots() {}

    public static Map<String, Object> from(QuestionResponse q) {
        var data = new LinkedHashMap<String, Object>();
        data.put("subject", q.subject());
        data.put("stem", q.stem());
        data.put("questionType", q.questionType());
        data.put("choices", q.choices());
        data.put("providedAnswerNumber", q.providedAnswerNumber());
        data.put("referenceDate", q.referenceDate());
        data.put("revision", q.revision());
        return data;
    }
}
