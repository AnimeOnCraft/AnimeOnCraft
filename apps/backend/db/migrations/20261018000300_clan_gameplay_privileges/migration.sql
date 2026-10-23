-- Explicit grants are required on a clean installation as well as restored development databases.
GRANT SELECT,INSERT,UPDATE ON clan_progression,clan_achievements,clan_events,clan_event_scores,
  clan_war_windows,clan_online_presence,clan_gameplay_events,clan_territory_control TO anime_app;
