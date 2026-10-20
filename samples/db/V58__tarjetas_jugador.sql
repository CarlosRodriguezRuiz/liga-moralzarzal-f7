CREATE TABLE config_tarjetas_categoria (
  tipo_id    BIGINT PRIMARY KEY REFERENCES equipo_tipos(id) ON DELETE CASCADE,
  habilitado BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE config_tarjetas_global (
  id           INT PRIMARY KEY DEFAULT 1,
  modo_equipos VARCHAR(20) NOT NULL DEFAULT 'TODOS',
  CONSTRAINT ck_config_tarjetas_modo CHECK (modo_equipos IN ('TODOS', 'ESPECIFICOS')),
  CONSTRAINT solo_una_fila_tarjetas CHECK (id = 1)
);
INSERT INTO config_tarjetas_global (id, modo_equipos) VALUES (1, 'TODOS');

CREATE TABLE config_tarjetas_equipos (
  equipo_id BIGINT PRIMARY KEY REFERENCES equipos(id) ON DELETE CASCADE
);
