package com.tornado.domain.skill.gateway;

/**
 * 技能脚本沙箱网关（domain 定义，infrastructure 实现）。
 * 封装物化落盘（.ps1 补 UTF-8 BOM）+ 解释器执行 + npm 依赖自愈，领域/应用层不感知文件系统与进程。
 */
public interface SkillScriptGateway {

    /** 在沙箱执行技能包内脚本，返回 "EXIT=n\n输出" 或错误说明 */
    String run(Long uid, String skillName, String scriptRelPath, String args);

    /** 删除技能时清理其沙箱目录 */
    void cleanup(Long uid, String skillName);
}
