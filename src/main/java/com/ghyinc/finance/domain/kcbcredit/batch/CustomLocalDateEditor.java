package com.ghyinc.finance.domain.kcbcredit.batch;

import java.beans.PropertyEditorSupport;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class CustomLocalDateEditor extends PropertyEditorSupport {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public String getAsText() {
        LocalDate value = (LocalDate) getValue();
        return value == null ? "" : value.format(FORMATTER);
    }

    @Override
    public void setAsText(String text) {
        if (text == null || text.isBlank()) {
            setValue(null);
            return;
        }
        try {
            setValue(LocalDate.parse(text.trim(), FORMATTER));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "잘못된 날짜 형식입니다. yyyyMMdd 형식이어야 합니다: " + text, e);
        }

        super.setAsText(text);
    }
}
