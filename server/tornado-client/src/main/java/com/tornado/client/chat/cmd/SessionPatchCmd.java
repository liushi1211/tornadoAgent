package com.tornado.client.chat.cmd;

import lombok.Data;

/** 修改会话（PATCH /api/sessions/{id}）：布尔语义入参，服务层转 0/1 入库 */
@Data
public class SessionPatchCmd {
    private String title;
    private Boolean pinned;
    private Boolean archived;
}
