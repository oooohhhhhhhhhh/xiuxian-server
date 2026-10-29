package com.mtxgdn.game.map;

/**
 * 野外总览（管理台用）。
 *
 * <p>字段名与原先的 Map 逐字一致；{@code dir} 是分片存储目录的绝对路径。</p>
 *
 * @param dir 分片存储目录
 * @param cachedChunks 内存中已缓存的分片数
 * @param fileReads 累计读文件次数
 * @param fileWrites 累计写文件次数
 * @param enabled 野外功能是否启用
 * @param chunkSize 分片边长
 * @param maxChunksFromOrigin 距原点最大分片数
 * @param worldSeed 世界种子
 * @param chunkCount 扫描到的分片数
 * @param pinnedChunks 已固化的分片数
 * @param exploredNodes 已探索节点数
 * @param resourceEntries 资源条目数
 * @param scanLimit 扫描上限
 */
public record WildernessStats(
        String dir,
        Integer cachedChunks,
        Long fileReads,
        Long fileWrites,
        boolean enabled,
        Integer chunkSize,
        Integer maxChunksFromOrigin,
        Long worldSeed,
        int chunkCount,
        int pinnedChunks,
        int exploredNodes,
        int resourceEntries,
        int scanLimit) {
}
