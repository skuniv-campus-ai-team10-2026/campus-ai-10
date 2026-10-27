-- Service-wide paid AI usage per budget period ("TOTAL" or a KST date such as "2026-10-31").
-- A call is reserved here BEFORE it is sent and settled with the provider's token counts afterwards,
-- so the limit survives restarts and holds under concurrent requests. Costs are estimates in micro-USD.
CREATE TABLE ai_usage_budget (
  period_key VARCHAR(20) NOT NULL PRIMARY KEY,
  llm_calls INT NOT NULL DEFAULT 0,
  llm_input_tokens BIGINT NOT NULL DEFAULT 0,
  llm_output_tokens BIGINT NOT NULL DEFAULT 0,
  embedding_calls INT NOT NULL DEFAULT 0,
  embedding_tokens BIGINT NOT NULL DEFAULT 0,
  reserved_micro_usd BIGINT NOT NULL DEFAULT 0,
  spent_micro_usd BIGINT NOT NULL DEFAULT 0,
  blocked_calls INT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
