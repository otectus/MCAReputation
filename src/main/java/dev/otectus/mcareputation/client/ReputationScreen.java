package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.network.ReputationNetwork;
import dev.otectus.mcareputation.reputation.ReputationMath;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The standalone standing screen (spec §28.2).
 *
 * <p>This is the guaranteed path to a player's standing: it works with no MCA: Quests and no MCA:
 * Conversations installed, reached from the Standing button on MCA's interaction screen or from the
 * keybind. Quests' Journal links here rather than drawing its own version, so the two can never
 * disagree.
 *
 * <p>Everything drawn comes from {@link ClientReputationData}, which is server-supplied. With no
 * snapshot the screen says so; it never invents a number to fill the space (§35.1).
 *
 * <h2>Small GUI scales</h2>
 *
 * <p>§28.2 requires no clipping, wrapped text, a scrolling list, reachable buttons, and at most one
 * nested modal at small sizes. The layout is therefore computed from the actual screen dimensions
 * rather than from constants: the panel shrinks to fit, deed text wraps to the panel width, and the
 * list is scissored and scrollable so its content never escapes its box.
 */
public final class ReputationScreen extends Screen {

    private static final int MAX_PANEL_WIDTH = 300;
    private static final int MIN_PANEL_WIDTH = 160;
    private static final int PADDING = 8;
    private static final int LINE = 10;

    @Nullable
    private final Screen parent;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    private double scroll;
    private int contentHeight;
    private int selectedCommunityIndex;
    private boolean requestedOnce;

    public ReputationScreen(@Nullable Screen parent) {
        super(Component.translatable("mcareputation.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(MIN_PANEL_WIDTH, Math.min(MAX_PANEL_WIDTH, width - 2 * PADDING));
        panelHeight = Math.min(height - 2 * PADDING, 200);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        Optional<ReputationNetwork.SelectedDetail> detail = ClientReputationData.selected();
        listTop = panelTop + 74 + (detail.flatMap(ReputationNetwork.SelectedDetail::tierDescription)
                .isPresent() ? LINE : 0);
        listBottom = panelTop + panelHeight - 26;

        syncSelectedIndex();

        // Content is measured here — not lazily during render — so a wheel event that arrives before
        // the first paint clamps against the real height, and stale scroll is clamped on refresh.
        contentHeight = detail.map(this::measureContent).orElse(0);
        scroll = ScrollMath.clampScroll(scroll, contentHeight, listBottom - listTop);

        List<ReputationNetwork.CommunitySummary> communities = ClientReputationData.communities();
        if (communities.size() > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> cycleCommunity(-1))
                    .bounds(panelLeft + PADDING, panelTop + panelHeight - 22, 20, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> cycleCommunity(1))
                    .bounds(panelLeft + PADDING + 22, panelTop + panelHeight - 22, 20, 18).build());
        }

        int closeWidth = Math.min(80, panelWidth / 3);
        addRenderableWidget(Button.builder(CommonComponentsCompat.done(), button -> onClose())
                .bounds(panelLeft + panelWidth - PADDING - closeWidth, panelTop + panelHeight - 22,
                        closeWidth, 18)
                .build());

        // Once per screen open, not once per rebuild: an empty reply rebuilds the widgets, and asking
        // again from every rebuild turned "you have no standing anywhere" into an endless poll.
        if (!requestedOnce && communities.isEmpty() && !ClientReputationData.awaitingSnapshot()) {
            requestedOnce = true;
            ClientReputationData.request(0, Optional.empty());
        }
    }

    @Override
    public void tick() {
        // Flushes any request parked behind the client-side cooldown (see RequestThrottle).
        ClientReputationData.tickRequests();
    }

    /** Keeps the selector index pointing at whatever the server actually sent as selected. */
    private void syncSelectedIndex() {
        Optional<ReputationNetwork.SelectedDetail> detail = ClientReputationData.selected();
        List<ReputationNetwork.CommunitySummary> communities = ClientReputationData.communities();
        selectedCommunityIndex = 0;
        if (detail.isPresent()) {
            for (int i = 0; i < communities.size(); i++) {
                if (communities.get(i).key().equals(detail.get().key())) {
                    selectedCommunityIndex = i;
                    return;
                }
            }
        }
    }

    private void cycleCommunity(int direction) {
        List<ReputationNetwork.CommunitySummary> communities = ClientReputationData.communities();
        if (communities.isEmpty()) {
            return;
        }
        selectedCommunityIndex = Math.floorMod(selectedCommunityIndex + direction, communities.size());
        scroll = 0;
        ClientReputationData.requestSelected(communities.get(selectedCommunityIndex).key());
    }

    /** Called when a fresh snapshot lands, so the layout follows the new content. */
    void onDataRefreshed() {
        syncSelectedIndex();
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xE0100010);
        graphics.renderOutline(panelLeft, panelTop, panelWidth, panelHeight, 0xFF6F4A87);

        Optional<ReputationNetwork.SelectedDetail> detail = ClientReputationData.selected();
        if (detail.isEmpty()) {
            renderEmptyState(graphics);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        renderHeader(graphics, detail.get());
        renderDeeds(graphics, detail.get(), mouseX, mouseY);
        renderFooter(graphics, detail.get());
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderEmptyState(GuiGraphics graphics) {
        Component message = ClientReputationData.awaitingSnapshot()
                ? Component.translatable("mcareputation.screen.loading")
                : Component.translatable("mcareputation.screen.no_community");
        int textWidth = panelWidth - 2 * PADDING;
        List<FormattedCharSequence> lines = font.split(message, textWidth);
        int y = panelTop + panelHeight / 2 - (lines.size() * LINE) / 2;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, panelLeft + PADDING, y, 0xBBBBBB, false);
            y += LINE;
        }
    }

    private void renderHeader(GuiGraphics graphics, ReputationNetwork.SelectedDetail detail) {
        int x = panelLeft + PADDING;
        int y = panelTop + PADDING;
        int textWidth = panelWidth - 2 * PADDING;

        Component communityName = detail.name().isEmpty()
                ? Component.translatable("mcareputation.community.unnamed", detail.key().villageId())
                : Component.literal(detail.name());
        graphics.drawString(font, communityName.copy().withStyle(ChatFormatting.BOLD), x, y, 0xFFE9B5, false);
        y += LINE + 2;

        // The dimension only earns a line when it is not the overworld, where it would be noise.
        if (!"minecraft:overworld".equals(detail.key().dimension().toString())) {
            graphics.drawString(font, Component.literal(detail.key().dimension().toString())
                    .withStyle(ChatFormatting.DARK_GRAY), x, y, 0x999999, false);
            y += LINE;
        }

        Component tierLine = McaReputationConfig.showExactScore()
                ? Component.translatable("mcareputation.screen.tier_with_score", detail.tierName(),
                        detail.score())
                : Component.translatable("mcareputation.screen.tier", detail.tierName());
        graphics.drawString(font, tierLine, x, y, 0xFFFFFF, false);
        y += LINE + 3;

        // The tier's authored description — one quiet line of what this standing means here.
        if (detail.tierDescription().isPresent()) {
            List<FormattedCharSequence> description = font.split(detail.tierDescription().get(), textWidth);
            if (!description.isEmpty()) {
                graphics.drawString(font, description.get(0), x, y, 0x8F86A0, false);
            }
            y += LINE;
        }

        renderProgress(graphics, detail, x, y, textWidth);
        y += 12;

        if (!detail.titles().isEmpty() || !ClientReputationData.globalTitles().isEmpty()) {
            Component titles = titleLine(detail.titles(), ClientReputationData.globalTitles());
            List<FormattedCharSequence> lines = font.split(titles, textWidth);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.drawString(font, lines.get(i), x, y, 0xC9B6E4, false);
                y += LINE;
            }
        }
    }

    private void renderProgress(GuiGraphics graphics, ReputationNetwork.SelectedDetail detail,
                                int x, int y, int barWidth) {
        boolean atTop = detail.nextTierId().isEmpty();
        float progress = atTop ? 1.0f
                : ReputationMath.progress(detail.score(), detail.tierThreshold(), detail.nextThreshold());
        graphics.fill(x, y, x + barWidth, y + 4, 0xFF2B1E33);
        graphics.fill(x, y, x + (int) (barWidth * progress), y + 4, 0xFFB07CD8);

        Component caption;
        if (atTop) {
            caption = Component.translatable("mcareputation.screen.progress_max");
        } else if (McaReputationConfig.showExactScore()) {
            caption = Component.translatable("mcareputation.screen.progress",
                    Math.max(0, detail.nextThreshold() - detail.score()),
                    detail.nextTierName().orElse(Component.empty()));
        } else {
            caption = Component.translatable("mcareputation.screen.progress_vague",
                    detail.nextTierName().orElse(Component.empty()));
        }
        graphics.drawString(font, caption, x, y + 6, 0x8F86A0, false);
    }

    /** Titles arrive from the server already resolved (§27.3); the client only joins them. */
    private Component titleLine(List<Component> villageTitles, List<Component> globalTitles) {
        var builder = Component.translatable("mcareputation.screen.titles").copy()
                .append(Component.literal(" "));
        List<Component> all = new ArrayList<>(villageTitles);
        globalTitles.forEach(title -> {
            if (!all.contains(title)) {
                all.add(title);
            }
        });
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) {
                builder.append(Component.literal(", "));
            }
            builder.append(all.get(i));
        }
        return builder;
    }

    /** The list's total pixel height, measured the same way it is drawn. */
    private int measureContent(ReputationNetwork.SelectedDetail detail) {
        int textWidth = panelWidth - 2 * PADDING - 6;
        int total = 0;
        for (ReputationNetwork.IncidentSummary incident : detail.incidents()) {
            total += font.split(incident.display(), textWidth).size() * LINE + LINE + 3;
        }
        if (detail.incidents().size() < detail.totalIncidents()) {
            total += LINE + 3;
        }
        return total;
    }

    private void renderDeeds(GuiGraphics graphics, ReputationNetwork.SelectedDetail detail,
                             int mouseX, int mouseY) {
        int x = panelLeft + PADDING;
        int textWidth = panelWidth - 2 * PADDING - 6;

        graphics.drawString(font, Component.translatable("mcareputation.screen.deeds"),
                x, listTop - 11, 0xFFE9B5, false);

        if (detail.incidents().isEmpty()) {
            graphics.drawString(font, Component.translatable("mcareputation.screen.no_deeds")
                    .copy().withStyle(ChatFormatting.GRAY), x, listTop + 4, 0x9A9A9A, false);
            contentHeight = 0;
            return;
        }

        // Scissor to the list box so a long ledger can never draw over the header or the buttons,
        // however small the GUI scale is.
        graphics.enableScissor(panelLeft + 1, listTop, panelLeft + panelWidth - 1, listBottom);
        int y = listTop - (int) scroll;
        int drawn = 0;
        for (ReputationNetwork.IncidentSummary incident : detail.incidents()) {
            int entryHeight = renderDeed(graphics, incident, x, y + drawn, textWidth);
            drawn += entryHeight + 3;
        }
        if (detail.incidents().size() < detail.totalIncidents()) {
            // §27.3 bounds the packet; the player must be able to tell "that's all" from "that's
            // all that fits" — a silently truncated ledger reads as a shorter life than they led.
            graphics.drawString(font, Component.translatable("mcareputation.screen.deeds_truncated",
                            detail.incidents().size(), detail.totalIncidents())
                    .withStyle(ChatFormatting.DARK_GRAY), x, y + drawn, 0x8F86A0, false);
            drawn += LINE + 3;
        }
        graphics.disableScissor();
        contentHeight = drawn;

        if (contentHeight > listBottom - listTop) {
            renderScrollbar(graphics);
        }
    }

    private int renderDeed(GuiGraphics graphics, ReputationNetwork.IncidentSummary incident,
                           int x, int y, int textWidth) {
        List<FormattedCharSequence> lines = font.split(incident.display(), textWidth);
        int used = 0;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, x, y + used, 0xDDDDDD, false);
            used += LINE;
        }

        // The meta line carries sign and words, never colour alone (§28.4).
        var meta = Component.translatable("mcareputation.age." + ageBucket(incident.ageTicks()));
        if (!"active".equals(incident.status())) {
            meta = meta.copy().append(Component.literal(" · "))
                    .append(Component.translatable("mcareputation.status." + incident.status()));
        }
        if (McaReputationConfig.showIncidentDeltas() && incident.contribution() != 0) {
            String sign = incident.contribution() > 0 ? "+" : "";
            meta = meta.copy().append(Component.literal(" · " + sign + incident.contribution()));
        }
        if (incident.pinned()) {
            meta = meta.copy().append(Component.literal(" · "))
                    .append(Component.translatable("mcareputation.screen.pinned"));
        }
        graphics.drawString(font, meta, x + 4, y + used, 0x8F86A0, false);
        return used + LINE;
    }

    /** Coarse age buckets keep the list readable and avoid pretending to a precision nobody needs. */
    private static String ageBucket(long ageTicks) {
        long days = ageTicks / 24000L;
        if (days <= 0) {
            return "today";
        }
        if (days == 1) {
            return "yesterday";
        }
        return days < 7 ? "days" : "long_ago";
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int trackHeight = listBottom - listTop;
        int barHeight = ScrollMath.thumbHeight(contentHeight, trackHeight);
        int barY = listTop + ScrollMath.thumbY(scroll, contentHeight, trackHeight);
        int barX = panelLeft + panelWidth - 5;
        graphics.fill(barX, listTop, barX + 3, listBottom, 0x40FFFFFF);
        graphics.fill(barX, barY, barX + 3, barY + barHeight, 0xFFB07CD8);
    }

    private void renderFooter(GuiGraphics graphics, ReputationNetwork.SelectedDetail detail) {
        List<ReputationNetwork.CommunitySummary> communities = ClientReputationData.communities();
        if (communities.size() <= 1) {
            return;
        }
        Component label = Component.translatable("mcareputation.screen.community_index",
                selectedCommunityIndex + 1, communities.size());
        graphics.drawString(font, label, panelLeft + PADDING + 46, panelTop + panelHeight - 17,
                0x9A9A9A, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY >= listTop && mouseY <= listBottom) {
            scroll = ScrollMath.clampScroll(scroll - delta * LINE * 2, contentHeight,
                    listBottom - listTop);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        // Returning to whatever opened this — MCA's interaction screen, the Journal, or nothing —
        // rather than always closing to the world (§28.2).
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Isolates the one vanilla component name that has moved between versions. */
    private static final class CommonComponentsCompat {
        static Component done() {
            return net.minecraft.network.chat.CommonComponents.GUI_DONE;
        }
    }
}
