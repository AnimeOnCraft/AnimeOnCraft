package ru.animeoncraft.integration.neoforge;

import java.util.*;

/**
 * Guild teams for B–S quests: 2–4 players from any clans, kept in memory (a restart or a logout leaves the team).
 * The leader invites, takes the team quest at the board; kills of every member count for the whole team.
 */
final class GuildParty {
    static final long INVITE_MILLIS = 60_000;
    static final class Party {
        UUID leader; final List<UUID> members = new ArrayList<>();
        String active = ""; long window, reward; int progress;
        Party(UUID leader) { this.leader = leader; members.add(leader); }
        void clear() { active = ""; window = 0; reward = 0; progress = 0; }
    }
    private record Invite(UUID leader, long expires) {}
    private final Map<UUID, Party> byMember = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();

    Party of(UUID member) { return byMember.get(member); }

    /** Returns an error for the inviter, or null when the invite was sent. */
    String invite(UUID from, UUID to, long now) {
        if (from.equals(to)) return "Нельзя пригласить себя.";
        var party = byMember.get(from);
        if (party != null && !party.leader.equals(from)) return "Приглашать может только лидер команды.";
        if (party != null && party.members.size() >= GuildRules.MAX_PARTY) return "В команде уже " + GuildRules.MAX_PARTY + " игрока.";
        if (byMember.containsKey(to)) return "Этот игрок уже в команде.";
        invites.put(to, new Invite(from, now + INVITE_MILLIS));
        return null;
    }
    /** Returns the joined team, or null when there is no live invite or the team is gone or full. */
    Party accept(UUID who, long now) {
        var invite = invites.remove(who);
        if (invite == null || invite.expires() < now || byMember.containsKey(who)) return null;
        var party = byMember.get(invite.leader());
        if (party == null) { party = new Party(invite.leader()); byMember.put(invite.leader(), party); }
        if (!party.leader.equals(invite.leader()) || party.members.size() >= GuildRules.MAX_PARTY) return null;
        party.members.add(who); byMember.put(who, party);
        return party;
    }
    /** Removes a member; the next member leads, a team of one dissolves. Returns the team left behind (or null). */
    Party leave(UUID who) {
        invites.remove(who);
        var party = byMember.remove(who); if (party == null) return null;
        party.members.remove(who);
        if (party.leader.equals(who) && !party.members.isEmpty()) party.leader = party.members.getFirst();
        if (party.members.size() <= 1) { for (var m : party.members) byMember.remove(m); party.clear(); return party.members.isEmpty() ? null : party; }
        return party;
    }
    void clear() { byMember.clear(); invites.clear(); }
}
