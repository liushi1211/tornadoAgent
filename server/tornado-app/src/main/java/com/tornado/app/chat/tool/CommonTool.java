package com.tornado.app.chat.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class CommonTool {

    @Tool(name = "get_current_time", description = "获取当时时间和时区")
    public String getCurrentTime() {
        return java.time.ZonedDateTime.now().toString();
    }
}
