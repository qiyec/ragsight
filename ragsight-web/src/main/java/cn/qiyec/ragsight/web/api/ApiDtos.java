package cn.qiyec.ragsight.web.api;

import java.util.List;
import java.util.Map;

/**
 * 给前端用的数据结构。
 *
 * <p><b>为什么不直接返回 domain 里的对象</b>：
 * 一是 domain 层不许引 Jackson（架构测试拦着），加序列化注解会破坏这条边界；
 * 二是前端要的形状和领域模型不一样——前端要"表头 + 行"这种好渲染的结构，
 * 领域模型要"能表达业务约束"的结构。硬用一套会两边都别扭。
 *
 * <p>所以这一层做一次转换。逻辑很薄，但它是**边界**：
 * 领域模型随便改，只要这里跟着改，前端不用动。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 一个可选的语料。 */
    public record CorpusInfo(String id, String name, String description) {
    }

    /** 一个可选方案（召回方式等）。 */
    public record Choice(String id, String label) {
    }

    /** 前端能调的所有参数，以及每个参数的默认值和可选项。 */
    public record Options(
            List<CorpusInfo> corpora,
            String defaultCorpusId,
            List<Choice> retrievers,
            List<Integer> chunkSizes,
            List<Integer> topKs,
            boolean realEmbeddingConfigured,
            String embeddingHint) {
    }

    /** 前端提交的诊断参数。字段可空，空就用默认值。 */
    public record DiagnoseRequest(
            String corpusId,
            Integer chunkSize,
            Integer chunkOverlap,
            Integer topK,
            Boolean includeRerank,
            Boolean embeddingEnabled,
            String embeddingModel,
            String embeddingBaseUrl) {
    }

    /** 诊断结果。表格用"表头 + 行"的扁平结构，前端直接渲染。 */
    public record DiagnoseResult(
            String corpusName,
            int documentCount,
            int caseCount,
            long negativeCaseCount,
            int experimentCount,
            String baselineName,
            List<String> metricNames,
            List<Row> rows,
            List<Finding> findings,
            Heatmap heatmap,
            Chart chart,
            long elapsedMillis) {

        /** 表格的一行：一次实验。 */
        public record Row(
                String chunkingLabel,
                String retrieverLabel,
                boolean reranked,
                boolean baseline,
                Map<String, Double> scores,
                Map<String, Double> deltas) {
        }

        /** 诊断结论 + 严重程度（前端据此上色）。 */
        public record Finding(String text, Level level) {

            public enum Level {
                /** 普通信息 */
                INFO,
                /** 值得做的事 */
                GOOD,
                /** 提醒，可能是假象 */
                WARN,
                /** 明显有问题 */
                BAD
            }
        }

        /**
         * 热力图：行是切分方案，列是召回方式，格子是指标值。
         *
         * <p>{@code values[row][col]} 就是格子里的数。用二维数组而不是
         * 一长串格子对象，是因为前端要算颜色深浅，拿到数组直接遍历最省事。
         */
        public record Heatmap(
                String metricName,
                List<String> rowLabels,
                List<String> columnLabels,
                double[][] values,
                double minValue,
                double maxValue) {
        }

        /**
         * 柱状图数据。
         *
         * <p>按全部实验排好序给出，前端直接画。
         */
        public record Chart(
                List<String> labels,
                double[] recall,
                double[] mrr,
                double[] ndcg,
                String bestLabel) {
        }
    }
}
