package com.tornado.client.chat.cmd;

import lombok.Data;

/** 新建会话（POST /api/sessions） */
@Data
public class SessionCreateCmd {
    private String modelId;
    private String title;
}
