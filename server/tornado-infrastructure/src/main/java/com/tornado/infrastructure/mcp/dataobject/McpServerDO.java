package com.tornado.infrastructure.mcp.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** mcp_server_config 表数据对象（MyBatis-Plus），仅存在于 infrastructure */
@Data
@TableName("mcp_server_config")
public class McpServerDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String transport;
    private String url;
    private String command;
    private String argsJson;
    private byte[] headersCipher;
    private Integer enabled;
    private String healthStatus;
    private String toolCacheJson;
    private LocalDateTime lastProbeAt;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
