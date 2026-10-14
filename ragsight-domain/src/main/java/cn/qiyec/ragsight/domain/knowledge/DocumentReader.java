package cn.qiyec.ragsight.domain.knowledge;

import java.util.List;

/**
 * 读文档的接口：把外部的文件读成 {@link Document}。
 *
 * <p>这里只约定"读出来是什么样"，不关心从哪读（本地目录、压缩包、对象存储）。
 *
 * <p><b>为什么不直接返回字符串</b>：一份文档要有 id 和来源，
 * 报告里才能说清"是哪个文件的哪一段出了问题"。如果只返回文本，
 * 这些信息就丢了，诊断结果没法定位到具体文件。
 */
public interface DocumentReader {

    /**
     * 把来源读成一组文档。
     *
     * <p>约定：
     * <ul>
     *   <li>读不到任何文档时返回空列表，而不是抛异常——空目录是合法输入</li>
     *   <li>单个文件读失败不应该让整批都失败，跳过并在日志里说明即可</li>
     *   <li>同样的来源读两次，结果必须一样</li>
     * </ul>
     *
     * @return 文档列表；顺序应稳定，便于结果复现
     */
    List<Document> readAll();

    /** 人类可读的来源描述，用于报告里说明这次用的是哪批文档。 */
    String describe();
}
