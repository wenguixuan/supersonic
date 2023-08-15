package com.tencent.supersonic.chat.api.pojo.request;


import com.tencent.supersonic.auth.api.authentication.pojo.User;
import com.tencent.supersonic.chat.api.pojo.SemanticParseInfo;
import lombok.Data;

@Data
public class ExecuteQueryReq {

    private User user;
    private Integer chatId;
    private String queryText;
    private SemanticParseInfo parseInfo;
    private boolean saveAnswer = true;
}