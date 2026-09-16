package com.tornado.client.error;

import org.springframework.http.HttpStatus;

/** 错误码表（详细设计 §9）：A-{模块}-{4位} */
public enum ErrorCode {
    // auth
    AUTH_INVALID_TOKEN("A-AUTH-0001", HttpStatus.UNAUTHORIZED, "token 无效或已过期"),
    AUTH_REFRESH_INVALID("A-AUTH-0002", HttpStatus.UNAUTHORIZED, "refresh token 已失效"),
    AUTH_BAD_CREDENTIALS("A-AUTH-0003", HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    AUTH_USER_EXISTS("A-AUTH-0004", HttpStatus.BAD_REQUEST, "用户名或邮箱已存在"),
    AUTH_USER_NOT_FOUND("A-AUTH-0005", HttpStatus.NOT_FOUND, "用户不存在"),
    // chat
    CHAT_QUOTA_EXCEEDED("A-CHAT-0001", HttpStatus.TOO_MANY_REQUESTS, "配额超限"),
    CHAT_SESSION_RUNNING("A-CHAT-0002", HttpStatus.CONFLICT, "会话正在生成中"),
    CHAT_HITL_EXPIRED("A-CHAT-0003", HttpStatus.GONE, "HITL 暂存过期或不存在，请重新发起"),
    CHAT_MODEL_ERROR("A-CHAT-0004", HttpStatus.BAD_GATEWAY, "模型端点异常"),
    CHAT_MODEL_UNKNOWN("A-CHAT-0005", HttpStatus.BAD_REQUEST, "未知模型"),
    // skill
    SKILL_PARSE_FAILED("A-SKILL-0001", HttpStatus.BAD_REQUEST, "SKILL.md 解析失败或技能重名"),
    // mcp
    MCP_HANDSHAKE_FAILED("A-MCP-0001", HttpStatus.BAD_GATEWAY, "MCP 握手失败"),
    // rag
    RAG_FILE_INVALID("A-RAG-0001", HttpStatus.PAYLOAD_TOO_LARGE, "文件超限或类型不符"),
    RAG_DOC_BUSY("A-RAG-0002", HttpStatus.CONFLICT, "文档处理中，不可删改"),
    RAG_DOC_NOT_FOUND("A-RAG-0003", HttpStatus.NOT_FOUND, "文档不存在"),
    // common
    INVALID_PARAM("A-COMMON-4000", HttpStatus.BAD_REQUEST, "参数校验失败"),
    NOT_FOUND("A-COMMON-4004", HttpStatus.NOT_FOUND, "资源不存在"),
    UNKNOWN("A-COMMON-5000", HttpStatus.INTERNAL_SERVER_ERROR, "未知错误");

    private final String code;
    private final HttpStatus status;
    private final String message;

    ErrorCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
}
