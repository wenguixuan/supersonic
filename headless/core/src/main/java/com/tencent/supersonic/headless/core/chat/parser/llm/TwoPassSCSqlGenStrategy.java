package com.tencent.supersonic.headless.core.chat.parser.llm;

import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.core.chat.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.headless.core.chat.query.llm.s2sql.LLMReq.SqlGenType;
import com.tencent.supersonic.headless.core.chat.query.llm.s2sql.LLMResp;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TwoPassSCSqlGenStrategy extends SqlGenStrategy {

    @Override
    public LLMResp generate(LLMReq llmReq) {
        //1.retriever sqlExamples and generate exampleListPool
        keyPipelineLog.info("llmReq:{}", llmReq);
        List<Map<String, String>> sqlExamples = exemplarManager.recallExemplars(llmReq.getQueryText(),
                optimizationConfig.getText2sqlExampleNum());

        List<List<Map<String, String>>> exampleListPool = promptGenerator.getExampleCombos(sqlExamples,
                optimizationConfig.getText2sqlFewShotsNum(), optimizationConfig.getText2sqlSelfConsistencyNum());

        //2.generator linking prompt,and parallel generate response.
        List<String> linkingPromptPool = promptGenerator.generatePromptPool(llmReq, exampleListPool, false);
        List<String> linkingResults = new CopyOnWriteArrayList<>();
        ChatLanguageModel chatLanguageModel = getChatLanguageModel(llmReq.getLlmConfig());
        linkingPromptPool.parallelStream().forEach(
                linkingPrompt -> {
                    Prompt prompt = PromptTemplate.from(JsonUtil.toString(linkingPrompt)).apply(new HashMap<>());
                    keyPipelineLog.info("step one request prompt:{}", prompt.toSystemMessage());
                    Response<AiMessage> linkingResult = chatLanguageModel.generate(prompt.toSystemMessage());
                    String result = linkingResult.content().text();
                    keyPipelineLog.info("step one model response:{}", result);
                    linkingResults.add(OutputFormat.getSchemaLink(result));
                }
        );
        List<String> sortedList = OutputFormat.formatList(linkingResults);
        Pair<String, Map<String, Double>> linkingMap = OutputFormat.selfConsistencyVote(sortedList);
        //3.generator sql prompt,and parallel generate response.
        List<String> sqlPromptPool = promptGenerator.generateSqlPromptPool(llmReq, sortedList, exampleListPool);
        List<String> sqlTaskPool = new CopyOnWriteArrayList<>();
        sqlPromptPool.parallelStream().forEach(sqlPrompt -> {
            Prompt linkingPrompt = PromptTemplate.from(JsonUtil.toString(sqlPrompt)).apply(new HashMap<>());
            keyPipelineLog.info("step two request prompt:{}", linkingPrompt.toSystemMessage());
            Response<AiMessage> sqlResult = chatLanguageModel.generate(linkingPrompt.toSystemMessage());
            String result = sqlResult.content().text();
            keyPipelineLog.info("step two model response:{}", result);
            sqlTaskPool.add(result);
        });
        //4.format response.
        Pair<String, Map<String, Double>> sqlMapPair = OutputFormat.selfConsistencyVote(sqlTaskPool);
        keyPipelineLog.info("linkingMap:{} sqlMap:{}", linkingMap, sqlMapPair.getRight());

        LLMResp llmResp = new LLMResp();
        llmResp.setQuery(llmReq.getQueryText());
        llmResp.setSqlRespMap(OutputFormat.buildSqlRespMap(sqlExamples, sqlMapPair.getRight()));
        return llmResp;
    }

    @Override
    public void afterPropertiesSet() {
        SqlGenStrategyFactory.addSqlGenerationForFactory(SqlGenType.TWO_PASS_AUTO_COT_SELF_CONSISTENCY, this);
    }
}
