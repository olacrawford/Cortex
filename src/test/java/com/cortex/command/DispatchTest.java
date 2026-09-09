package com.cortex.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class DispatchTest {

    @ParameterizedTest
    @CsvSource({
            "'', '', false",
            "'   ', '', false",
            "hello, '', false",
            "'/', '', true",
            "/help, help, true",
            "'  /HELP  ', help, true",
            "'/help xx', '', true",
            "'/help  ', help, true",
            "//double, /double, true",
            "'/ /help', '', true"
    })
    void parse各输入形态(String input, String name, boolean isSlash) {
        Dispatch.Parsed p = Dispatch.parse(input);
        assertEquals(name, p.name(), "input=" + input);
        assertEquals(isSlash, p.isSlash(), "input=" + input);
    }

    @Test
    void null输入走非斜杠路径() {
        assertFalse(Dispatch.parse(null).isSlash());
    }
}
