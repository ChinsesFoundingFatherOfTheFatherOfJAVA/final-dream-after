package jack.digityhuman.atualcombattraining.agent;

/** Dify / native 共用的编排结果。 */
public record AgentStreamResult(String conversationId, String taskId, String messageId,
                                String answer, String errorMessage) {
    public boolean failed() {
        return errorMessage != null && !errorMessage.isBlank();
    }
}
