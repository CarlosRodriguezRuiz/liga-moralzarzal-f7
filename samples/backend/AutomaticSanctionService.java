package com.ligalocal.api.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AutomaticSanctionService {

  private static final String AUTO_PREFIX = "[AUTO]";

  /**
   * Estado de una sancion (ACTIVA / CUMPLIDA) para usar dentro de un SELECT. Espera los alias
   * s = sanciones_acta, me = miembros_equipo del sancionado, u = su usuario, p = partido de
   * origen (LEFT JOIN) y c = competicion de la sancion.
   *
   * En ligas por jornadas la sancion se cumple en partidos que el equipo del jugador JUEGA: sigue
   * activa mientras a su equipo le quede algun partido sin acta final dentro del rango de
   * jornadas. Una jornada de descanso no cuenta. En copas (el rango son fases, no jornadas) se
   * mantiene el criterio anterior: activa mientras la ultima fase sancionada no se haya superado.
   */
  public static final String ESTADO_SQL = """
      CASE
        WHEN COALESCE(u.jornadas_sancion_pendientes, 0) > 0 THEN 'ACTIVA'
        WHEN c.tipo NOT IN ('COPA', 'SUPERCOPA') AND COALESCE(p.tipo_partido, 'LIGA') NOT LIKE '%COPA%' THEN
          CASE WHEN EXISTS (
                 SELECT 1 FROM partidos px
                  WHERE px.competicion_id = c.id
                    AND px.jornada BETWEEN s.jornada_inicio AND s.jornada_fin
                    AND (px.equipo_local_id = me.equipo_id OR px.equipo_visitante_id = me.equipo_id)
                    AND px.estado NOT IN ('CANCELADO', 'SUSPENDIDO')
                    AND NOT EXISTS (
                      SELECT 1 FROM actas ax
                       WHERE ax.partido_id = px.id
                         AND ax.estado IN ('VALIDADA', 'OFICIAL', 'CERRADA', 'CERRADA_AUTOMATICAMENTE', 'WALKOVER')))
               THEN 'ACTIVA' ELSE 'CUMPLIDA' END
        WHEN s.jornada_fin >= COALESCE(
          (SELECT MIN(p2.jornada)
             FROM partidos p2
            WHERE p2.competicion_id = c.id
              AND p2.jornada IS NOT NULL
              AND p2.estado NOT IN ('CANCELADO', 'SUSPENDIDO')
              AND NOT EXISTS (
                SELECT 1 FROM actas a2
                 WHERE a2.partido_id = p2.id
                   AND a2.estado IN ('VALIDADA', 'OFICIAL', 'CERRADA', 'CERRADA_AUTOMATICAMENTE', 'WALKOVER'))),
          (SELECT COALESCE(MAX(p3.jornada), 0) + 1
             FROM partidos p3
            WHERE p3.competicion_id = c.id)
        ) THEN 'ACTIVA'
        ELSE 'CUMPLIDA'
      END
      """;

  private final JdbcTemplate jdbc;

  public AutomaticSanctionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void applyForActa(Long actaId) {
    ActaContext ctx = loadContext(actaId);
    if (ctx == null || ctx.jornada() == null) return;

    deletePreviousAutomaticSanctions(actaId);
    applyDoubleYellowSanctions(actaId, ctx);
    applyYellowAccumulationSanctions(actaId, ctx);
  }

  public void deleteAutomaticSanctionsForActa(Long actaId) {
    deletePreviousAutomaticSanctions(actaId);
  }

  /**
   * Las sanciones que pone el admin (rojas resueltas, con su resolucion) cuelgan del id del evento
   * ROJA, y los eventos se recrean con ids nuevos cada vez que se guarda el acta o al reiniciarla.
   * Aqui se vuelven a colgar de la roja del mismo jugador en este partido. Si la roja ya no esta
   * (se quito al corregir el acta), la sancion se retira: no se sanciona por una tarjeta que no existe.
   */
  public void relinkAdminSanctions(Long actaId, Long partidoId) {
    // Sancion del admin de este partido cuyo evento ya no existe
    String huerfana = """
        s.partido_id = ?
           AND s.acta_evento_id IS NOT NULL
           AND COALESCE(s.notas_admin, '') NOT LIKE ?
           AND NOT EXISTS (SELECT 1 FROM eventos_partido ex WHERE ex.id = s.acta_evento_id)
        """;
    String rojaDelJugador = """
        FROM eventos_partido e
         WHERE e.acta_id = ? AND e.tipo_evento = 'ROJA' AND e.miembro_equipo_id = s.miembro_equipo_id
        """;
    jdbc.update(
        "UPDATE sanciones_acta s SET acta_evento_id = (SELECT MIN(e.id) " + rojaDelJugador + ")"
            + " WHERE " + huerfana
            + " AND EXISTS (SELECT 1 " + rojaDelJugador + ")",
        actaId, partidoId, AUTO_PREFIX + "%", actaId);
    jdbc.update("DELETE FROM sanciones_acta s WHERE " + huerfana, partidoId, AUTO_PREFIX + "%");
  }

  /** El jugador tiene alguna sancion sin cumplir (mismo criterio que el listado de sanciones). */
  public boolean hasActiveSanction(Long memberId) {
    Boolean active = jdbc.queryForObject(
        """
        SELECT EXISTS (
          SELECT 1
            FROM sanciones_acta s
            JOIN miembros_equipo me ON me.id = s.miembro_equipo_id
            JOIN usuarios u         ON u.id  = me.usuario_id
            LEFT JOIN partidos p    ON p.id  = s.partido_id
            JOIN competiciones c    ON c.id  = COALESCE(s.competicion_id, p.competicion_id)
           WHERE s.miembro_equipo_id = ?
             AND s.jornada_fin IS NOT NULL
             AND (""" + ESTADO_SQL + """
             ) = 'ACTIVA'
        )
        """,
        Boolean.class, memberId);
    return Boolean.TRUE.equals(active);
  }

  /**
   * Ajusta un rango de jornadas elegido a mano (roja resuelta por el admin, sancion directa) para
   * que cubra partidos que el equipo del jugador realmente juega: si en la jornada de inicio su
   * equipo descansa, el rango se desplaza a sus siguientes partidos. Devuelve {inicio, fin}.
   * En copas, o si al equipo no le quedan partidos suficientes, deja el rango como venia.
   */
  public int[] alignRangeToTeamMatches(Long memberId, Long competicionId, int inicio, int fin) {
    int partidos = fin - inicio + 1;
    if (memberId == null || competicionId == null || partidos <= 0) return new int[] {inicio, fin};
    List<Integer> jornadas = upcomingTeamJornadas(memberId, competicionId, inicio);
    if (jornadas.size() < partidos) return new int[] {inicio, fin};
    return new int[] {jornadas.get(0), jornadas.get(partidos - 1)};
  }

  /** Jornadas (desde la indicada) en las que al equipo del jugador le queda un partido de liga por jugar. */
  public List<Integer> upcomingTeamJornadas(Long memberId, Long competicionId, int desdeJornada) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT p.jornada
          FROM partidos p
          JOIN competiciones c    ON c.id  = p.competicion_id
          JOIN miembros_equipo me ON me.id = ?
         WHERE p.competicion_id = ?
           AND c.tipo NOT IN ('COPA', 'SUPERCOPA')
           AND COALESCE(p.tipo_partido, 'LIGA') NOT LIKE '%COPA%'
           AND p.jornada >= ?
           AND (p.equipo_local_id = me.equipo_id OR p.equipo_visitante_id = me.equipo_id)
           AND p.estado NOT IN ('CANCELADO', 'SUSPENDIDO')
           AND NOT EXISTS (
             SELECT 1 FROM actas a
              WHERE a.partido_id = p.id
                AND a.estado IN ('VALIDADA', 'OFICIAL', 'CERRADA', 'CERRADA_AUTOMATICAMENTE', 'WALKOVER'))
         ORDER BY p.jornada
        """,
        Integer.class, memberId, competicionId, desdeJornada);
  }

  /**
   * Nombre del jugador si esta sancionado para ese partido, o null si puede jugarlo. Solo se
   * comprueba en partidos de liga por jornadas: en copa el rango de la sancion son fases y esa
   * comprobacion la hace la pantalla del acta.
   */
  public String sanctionedPlayerName(Long memberId, Long partidoId) {
    return jdbc.query(
        """
        SELECT u.nombre || ' ' || u.apellido1 AS jugador
          FROM sanciones_acta s
          JOIN miembros_equipo me ON me.id = s.miembro_equipo_id
          JOIN usuarios u         ON u.id  = me.usuario_id
          LEFT JOIN partidos po   ON po.id = s.partido_id
          JOIN partidos m         ON m.id  = ?
          JOIN competiciones c    ON c.id  = m.competicion_id
         WHERE s.miembro_equipo_id = ?
           AND COALESCE(s.competicion_id, po.competicion_id) = m.competicion_id
           AND c.tipo NOT IN ('COPA', 'SUPERCOPA')
           AND COALESCE(m.tipo_partido, 'LIGA') NOT LIKE '%COPA%'
           AND m.jornada IS NOT NULL
           AND m.jornada BETWEEN s.jornada_inicio AND s.jornada_fin
           AND (s.partido_id IS NULL OR s.partido_id <> m.id)
         LIMIT 1
        """,
        (rs, rowNum) -> rs.getString("jugador"), partidoId, memberId
    ).stream().findFirst().orElse(null);
  }

  private void applyDoubleYellowSanctions(Long actaId, ActaContext ctx) {
    if (ctx.doubleYellowGames() == null || ctx.doubleYellowGames() <= 0) return;
    List<CardEvent> events = jdbc.query(
        """
        SELECT e.id, e.miembro_equipo_id, e.partido_id
          FROM eventos_partido e
         WHERE e.acta_id = ?
           AND e.tipo_evento = 'SEGUNDA_AMARILLA'
           AND e.miembro_equipo_id IS NOT NULL
        """,
        (rs, rowNum) -> new CardEvent(
            rs.getLong("id"),
            rs.getLong("miembro_equipo_id"),
            rs.getLong("partido_id")
        ),
        actaId
    );

    for (CardEvent event : events) {
      createAutomaticSanction(
          event,
          ctx,
          ctx.doubleYellowGames(),
          AUTO_PREFIX + " Sancion por doble amarilla."
      );
    }
  }

  private void applyYellowAccumulationSanctions(Long actaId, ActaContext ctx) {
    if (ctx.yellowThreshold() == null || ctx.yellowThreshold() <= 0) return;
    if (ctx.yellowAccumulationGames() == null || ctx.yellowAccumulationGames() <= 0) return;

    List<YellowAccumulationCandidate> candidates = jdbc.query(
        """
        SELECT e.miembro_equipo_id,
               MAX(e.id) AS evento_id,
               MAX(e.partido_id) AS partido_id,
               CASE WHEN bool_or(e.tipo_evento = 'SEGUNDA_AMARILLA') THEN 2 ELSE COUNT(*) END AS amarillas_acta
          FROM eventos_partido e
         WHERE e.acta_id = ?
           AND e.tipo_evento IN ('AMARILLA', 'SEGUNDA_AMARILLA')
           AND e.miembro_equipo_id IS NOT NULL
         GROUP BY e.miembro_equipo_id
        """,
        (rs, rowNum) -> new YellowAccumulationCandidate(
            rs.getLong("miembro_equipo_id"),
            rs.getLong("evento_id"),
            rs.getLong("partido_id"),
            rs.getInt("amarillas_acta")
        ),
        actaId
    );

    for (YellowAccumulationCandidate candidate : candidates) {
      int before = yellowCountBeforeActa(candidate.miembroEquipoId(), ctx.competitionId(), actaId);
      int after = before + candidate.yellowCardsInActa();
      int sanctionsBefore = before / ctx.yellowThreshold();
      int sanctionsAfter = after / ctx.yellowThreshold();
      int sanctionsDue = sanctionsAfter - sanctionsBefore;
      if (sanctionsDue <= 0) continue;

      int games = sanctionsDue * ctx.yellowAccumulationGames();
      createAutomaticSanction(
          new CardEvent(candidate.eventId(), candidate.miembroEquipoId(), candidate.partidoId()),
          ctx,
          games,
          AUTO_PREFIX + " Sancion por acumulacion de amarillas: " + after + " amarillas."
      );
    }
  }

  private int yellowCountBeforeActa(Long memberId, Long competitionId, Long actaId) {
    Integer count = jdbc.queryForObject(
        """
        SELECT COALESCE(SUM(x.amarillas), 0)
          FROM (
            -- Por acta: una doble amarilla son 2 amarillas y punto, aunque en un acta antigua
            -- se hubiera quedado marcada tambien la primera.
            SELECT CASE WHEN bool_or(e.tipo_evento = 'SEGUNDA_AMARILLA') THEN 2 ELSE COUNT(*) END AS amarillas
              FROM eventos_partido e
              JOIN actas a ON a.id = e.acta_id
              JOIN partidos p ON p.id = a.partido_id
             WHERE e.miembro_equipo_id = ?
               AND p.competicion_id = ?
               AND a.id <> ?
               AND a.estado IN ('OFICIAL', 'VALIDADA', 'CERRADA', 'CERRADA_AUTOMATICAMENTE')
               AND e.tipo_evento IN ('AMARILLA', 'SEGUNDA_AMARILLA')
             GROUP BY a.id
          ) x
        """,
        Integer.class,
        memberId,
        competitionId,
        actaId
    );
    return count != null ? count : 0;
  }

  private void createAutomaticSanction(CardEvent event, ActaContext ctx, int totalGames, String note) {
    if (totalGames <= 0) return;
    // La sancion se cumple en partidos que el equipo juega: el rango va de su proximo partido
    // al N-esimo. Si la jornada siguiente descansa, la sancion empieza en la otra.
    // Las sanciones automaticas (amarillas) son leves y NO pasan a la siguiente competicion: lo
    // que no quepa en lo que queda de liga se pierde. Solo arrastran jornadas las sanciones que
    // resuelve el SuperAdmin, que decide cuantas con "jornadas adicionales".
    List<Integer> proximas = jdbc.queryForList(
        """
        SELECT DISTINCT p.jornada
          FROM partidos p
          JOIN miembros_equipo me ON me.id = ?
         WHERE p.competicion_id = ?
           AND p.jornada > ?
           AND (p.equipo_local_id = me.equipo_id OR p.equipo_visitante_id = me.equipo_id)
           AND p.estado NOT IN ('CANCELADO', 'SUSPENDIDO')
           AND NOT EXISTS (
             SELECT 1 FROM actas a
              WHERE a.partido_id = p.id
                AND a.estado IN ('VALIDADA', 'OFICIAL', 'CERRADA', 'CERRADA_AUTOMATICAMENTE', 'WALKOVER'))
         ORDER BY p.jornada
        """,
        Integer.class, event.miembroEquipoId(), ctx.competitionId(), ctx.jornada());
    int gamesInCompetition = Math.min(totalGames, proximas.size());
    if (gamesInCompetition == 0) return; // a su equipo no le quedan partidos: no hay nada que cumplir
    int pendingGames = 0;
    int nextJornada = proximas.get(0);
    int jornadaFin = proximas.get(gamesInCompetition - 1);

    jdbc.update(
        """
        INSERT INTO sanciones_acta
            (acta_evento_id, miembro_equipo_id, partido_id, competicion_id,
             jornadas_sancion, jornada_inicio, jornada_fin, notas_admin, jornadas_pendientes_generadas)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        event.eventId(),
        event.miembroEquipoId(),
        event.partidoId(),
        ctx.competitionId(),
        gamesInCompetition,
        nextJornada,
        jornadaFin,
        note,
        pendingGames
    );

    if (pendingGames > 0) {
      jdbc.update(
          """
          UPDATE usuarios u
             SET jornadas_sancion_pendientes = jornadas_sancion_pendientes + ?
            FROM miembros_equipo me
           WHERE me.id = ? AND u.id = me.usuario_id
          """,
          pendingGames,
          event.miembroEquipoId()
      );
    }
  }

  private void deletePreviousAutomaticSanctions(Long actaId) {
    List<AutomaticSanctionToDelete> previous = jdbc.query(
        """
        SELECT s.id,
               s.miembro_equipo_id,
               COALESCE(s.jornadas_pendientes_generadas,
                 GREATEST(0, s.jornadas_sancion - GREATEST(0, COALESCE(s.jornada_fin, 0) - COALESCE(s.jornada_inicio, 0) + 1))) AS pendientes
          FROM sanciones_acta s
         WHERE s.notas_admin LIKE ?
           AND s.acta_evento_id IN (
             SELECT e.id FROM eventos_partido e WHERE e.acta_id = ?
           )
        """,
        (rs, rowNum) -> new AutomaticSanctionToDelete(
            rs.getLong("id"),
            rs.getLong("miembro_equipo_id"),
            rs.getInt("pendientes")
        ),
        AUTO_PREFIX + "%",
        actaId
    );

    for (AutomaticSanctionToDelete row : previous) {
      if (row.pendingGames() > 0) {
        jdbc.update(
            """
            UPDATE usuarios u
               SET jornadas_sancion_pendientes = GREATEST(0, jornadas_sancion_pendientes - ?)
              FROM miembros_equipo me
             WHERE me.id = ? AND u.id = me.usuario_id
            """,
            row.pendingGames(),
            row.miembroEquipoId()
        );
      }
    }

    jdbc.update(
        """
        DELETE FROM sanciones_acta s
         WHERE s.notas_admin LIKE ?
           AND s.acta_evento_id IN (
             SELECT e.id FROM eventos_partido e WHERE e.acta_id = ?
           )
        """,
        AUTO_PREFIX + "%",
        actaId
    );
  }

  private ActaContext loadContext(Long actaId) {
    return jdbc.query(
        """
        SELECT p.competicion_id,
               p.jornada,
               (SELECT MAX(p2.jornada) FROM partidos p2 WHERE p2.competicion_id = p.competicion_id) AS max_jornada,
               rc.amarillas_para_sancion,
               rc.partidos_sancion_por_acumulacion_amarillas,
               rc.doble_amarilla_partidos_sancion
          FROM actas a
          JOIN partidos p ON p.id = a.partido_id
          JOIN competiciones c ON c.id = p.competicion_id
          JOIN reglas_competicion rc ON rc.id = c.reglas_competicion_id
         WHERE a.id = ?
        """,
        (rs, rowNum) -> new ActaContext(
            rs.getLong("competicion_id"),
            rs.getObject("jornada", Integer.class),
            rs.getObject("max_jornada", Integer.class),
            rs.getObject("amarillas_para_sancion", Integer.class),
            rs.getObject("partidos_sancion_por_acumulacion_amarillas", Integer.class),
            rs.getObject("doble_amarilla_partidos_sancion", Integer.class)
        ),
        actaId
    ).stream().findFirst().orElse(null);
  }

  private record ActaContext(
      Long competitionId,
      Integer jornada,
      Integer maxJornada,
      Integer yellowThreshold,
      Integer yellowAccumulationGames,
      Integer doubleYellowGames
  ) {}

  private record CardEvent(Long eventId, Long miembroEquipoId, Long partidoId) {}

  private record YellowAccumulationCandidate(
      Long miembroEquipoId,
      Long eventId,
      Long partidoId,
      int yellowCardsInActa
  ) {}

  private record AutomaticSanctionToDelete(Long id, Long miembroEquipoId, int pendingGames) {}
}
