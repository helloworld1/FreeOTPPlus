package org.fedorahosted.freeotp;

import java.util.List;

/**
 * The saved tokens to storage
 */
public class SavedTokens {
    private List<Token> tokens;

    private List<String> tokenOrder;

    public SavedTokens(List<Token> tokens, List<String> tokenOrder) {
        this.tokens = tokens;
        this.tokenOrder = tokenOrder;
    }

    public List<Token> getTokens() {
        return tokens;
    }

    public List<String> getTokenOrder() {
        return tokenOrder;
    }
}
