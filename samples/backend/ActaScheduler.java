package com.ligalocal.api.scheduler;

import com.ligalocal.api.service.AutomaticSanctionService;
import com.ligalocal.api.service.CopaAdvancementService;
import com.ligalocal.api.service.PlayerCardService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job que revisa cada hora las actas en estado PENDIENTE y las cierra
 * automaticamente cuando el plazo de reclamacion ha expirado.
 */
@Component
public class ActaScheduler {

  private static final Logger log = LoggerFactory.getLogger(ActaScheduler.class);

  private final JdbcTemplate jdbc;
  private final CopaAdvancementService copaAdvancement;
  private final AutomaticSanctionService automaticSanctions;
  private final PlayerCardService playerCards;

  public ActaScheduler(JdbcTemplate jdbc, CopaAdvancementService copaAdvancement, AutomaticSanctionService automaticSanctions, PlayerCardService playerCards) {
    this.jdbc = jdbc;
    this.copaAdvancement = copaAdvancement;
    this.automaticSanctions = automaticSanctions;
    this.playerCards = playerCards;
  }

  @Scheduled(fixedDelay = 3_600_000)
  @Transactional
  public void closePendingActas() {
    List<Long> ids = jdbc.queryForList("""
        SELECT a.id
          FROM actas a
          JOIN partidos p ON p.id = a.partido_id
          JOIN competiciones c ON c.id = p.competicion_id
          LEFT JOIN reglas_competicion rc ON rc.id = c.reglas_competicion_id
         WHERE a.estado = 'PENDIENTE'
           AND a.fecha_enviada_pendiente IS NOT NULL
           -- plazo propio del acta si se modifico tras una reclamacion; si no, el de la competicion
           AND a.fecha_enviada_pendiente + (COALESCE(a.plazo_reclamacion_horas, rc.tiempo_reclamacion_acta_horas, 48) * INTERVAL '1 hour') < now()
           -- Un acta con una reclamacion sin resolver queda bloqueada: no se cierra sola.
           AND NOT EXISTS (
             SELECT 1 FROM impugnaciones_acta i WHERE i.acta_id = a.id AND i.gestionada = FALSE)
        """, Long.class);

    int closed = 0;
    for (Long id : ids) {
      int updated = jdbc.update("UPDATE actas SET estado = 'OFICIAL' WHERE id = ? AND estado = 'PENDIENTE'", id);
      if (updated > 0) {
        automaticSanctions.applyForActa(id);
        playerCards.applyActaResult(id);
        copaAdvancement.advanceIfOfficialActa(id);
        copaAdvancement.advanceSupercopaGroupIfComplete(id);
      }
      closed += updated;
    }
    if (closed > 0) {
      log.info("[ActaScheduler] {} acta(s) PENDIENTE cerradas automaticamente -> OFICIAL", closed);
    }
  }
}
