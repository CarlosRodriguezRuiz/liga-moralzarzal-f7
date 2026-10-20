-- Permite sanciones directas sin acta/evento previo
ALTER TABLE sanciones_acta
  ALTER COLUMN acta_evento_id DROP NOT NULL,
  ALTER COLUMN partido_id     DROP NOT NULL;

ALTER TABLE sanciones_acta
  ADD COLUMN IF NOT EXISTS competicion_id BIGINT REFERENCES competiciones(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_sanciones_competicion ON sanciones_acta(competicion_id);
