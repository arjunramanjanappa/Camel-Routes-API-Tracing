package io.traceguard.api;

import java.util.List;

/**
 * One Jira ticket's share of an API's change in the release, consolidated across every release commit on the
 * API's route XML, templates and shared classes: the developers who committed under it (in first-seen order)
 * and the distinct commit messages (subject after the bracket tokens).
 *
 * @param ticket     the Jira key from the commit subject ({@code [SGMB-4601]…}), or null when the commits
 *                   carried no ticket token ("no Jira")
 * @param developers distinct commit authors under this ticket, as git records them
 * @param messages   distinct commit messages under this ticket, first line only
 */
public record JiraRef(String ticket, List<String> developers, List<String> messages) {
}
