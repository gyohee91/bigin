package com.ghyinc.finance.global.common;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TextUtils {

    /** null 이거나 공백뿐이면 null, 아니면 그대로 */
    public String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
