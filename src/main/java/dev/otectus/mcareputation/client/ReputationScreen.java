package dev.otectus.mcareputation.client;

import dev.otectus.mcareputation.McaReputationConfig;
import dev.otectus.mcareputation.api.VillagerOpinion;
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
 * <p>The detailed community and the community list are <b>not the same set</b>. The server may detail
 * a village the player has no record for -- the one they are standing in, or the villager they just
 * looked at -- and the list carries only communities with a record. {@link SelectorMath} owns both
 * consequences: whether the selector has anywhere to go, and where a cycle lands from a selection
 * that is not in the list.
 *
 * <h2>Presentation</h2>
 *
 * <p>§28.2 asks for MCA's visual language rather than a visually unrelated menu, so the frame, the
 * ledger's well, the progress bar and the scroller are all drawn from {@link GuiTextures} in
 * vanilla's container idiom, and the text uses vanilla's two label colours (see {@link GuiPalette}).
 * The player reaches this screen one click from MCA's own interaction screen; it should look like
 * the same game.
 *
 * <h2>Small GUI scales</h2>
 *
 * <p>§28.2 requires no clipping, wrapped text, a scrolling list, reachable buttons, and at most one
 * nested modal at small sizes. The layout is therefore computed from the actual screen dimensions
 * rather than from constants: the panel shrinks to fit, deed text wraps to the panel width, and the
 * list is scissored and scrollable so its content never escapes its box.
 *
 * <h2>Laid out once, drawn many times</h2>
 *
 * <p>The header lines and the deed rows are wrapped in {@link #init()} and stored, not re-measured
 * on every frame. That is not only cheaper: it is the only way the drawn height and the height the
 * scrollbar is scaled against cannot drift apart, which they previously could, because measuring
 * and drawing were two separate walks over the same data.
 */
public final class ReputationScreen extends Screen {

    private static final int MAX_PANEL_WIDTH = 300;
    private static final int MIN_PANEL_WIDTH = 160;
    private static final int PADDING = 8;
    private static final int LINE = 10;

    /** Vanilla's container-title inset, so the village name sits where a chest's label does. */
    private static final int HEADER_TOP = 6;
    private static final int NAME_GAP = LINE + 2;
    private static final int TIER_GAP = LINE + 3;
    /** The progress track, its caption, and a little air below. */
    private static final int PROGRESS_GAP = GuiTextures.PROGRESS_HEIGHT + 1 + LINE + 2;
    /** Rule, ledger label, and the gaps around them, between the header and the well. */
    private static final int LABEL_BLOCK = GuiTextures.RULE_HEIGHT + 3 + LINE + 1;

    /** Height reserved at the foot of the panel for the button strip. */
    private static final int FOOTER_HEIGHT = 26;
    private static final int BUTTON_HEIGHT = 18;
    private static final int ARROW_WIDTH = 20;
    private static final int ARROW_GAP = 2;

    /**
     * The scroller channel, reserved beside the ledger whether or not it overflows.
     *
     * <p>Reserving it conditionally would be circular — the wrap width would depend on the wrap —
     * and the measured and drawn heights would disagree for exactly the lists that sit on the
     * boundary. Vanilla reserves it unconditionally for the same reason.
     */
    private static final int SCROLL_CHANNEL = GuiTextures.GROOVE_WIDTH;

    /** The smallest well that can still show one line of text without clipping it (§28.2). */
    private static final int MIN_WELL_HEIGHT = LINE + 6;

    /** A laid-out header line; a {@code null} text is where the progress bar goes. */
    private record HeaderLine(@Nullable FormattedCharSequence text, int colour, int height) {
    }

    /** A laid-out deed: its wrapped description, and the one meta line beneath it. */
    private record DeedLine(List<FormattedCharSequence> body, FormattedCharSequence meta) {
        int height() {
            return body.size() * LINE + LINE;
        }
    }

    @Nullable
    private final Screen parent;

    private final List<HeaderLine> headerLines = new ArrayList<>();
    private final List<DeedLine> deedLines = new ArrayList<>();
    @Nullable
    private FormattedCharSequence truncationNote;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int headerBottom;
    private int wellLeft;
    private int wellRight;
    private int wellTop;
    private int wellBottom;
    private boolean hasWell;
    private int listTop;
    private int listBottom;
    private int deedLeft;
    private int deedWidth;
    private int grooveX;
    private double scroll;
    private int contentHeight;
    private int selectedCommunityIndex;
    private boolean requestedOnce;
    private boolean draggingScroll;
    private double dragOffset;

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
        syncSelectedIndex();

        int textWidth = panelWidth - 2 * PADDING;
        headerLines.clear();
        detail.ifPresent(selected -> layoutHeader(selected, textWidth));
        headerBottom = panelTop + HEADER_TOP;
        for (HeaderLine line : headerLines) {
            headerBottom += line.height();
        }

        wellLeft = panelLeft + 6;
        wellRight = panelLeft + panelWidth - 6;
        wellBottom = panelTop + panelHeight - FOOTER_HEIGHT;
        // The well takes what is left below the header, but never so little that a line of text
        // would be clipped: at punishing GUI scales it climbs into the header instead (§28.2).
        wellTop = Math.max(panelTop + HEADER_TOP,
                Math.min(headerBottom + LABEL_BLOCK, wellBottom - MIN_WELL_HEIGHT));
        hasWell = wellBottom - wellTop >= MIN_WELL_HEIGHT;
        listTop = wellTop + 2;
        listBottom = wellBottom - 2;
        grooveX = wellRight - 1 - SCROLL_CHANNEL;
        deedLeft = wellLeft + 3;
        deedWidth = Math.max(1, grooveX - 3 - deedLeft);

        // Content is measured here — not lazily during render — so a wheel event that arrives before
        // the first paint clamps against the real height, and stale scroll is clamped on refresh.
        deedLines.clear();
        truncationNote = null;
        detail.ifPresent(this::layoutDeeds);
        contentHeight = 0;
        for (DeedLine deed : deedLines) {
            contentHeight += deed.height() + 3;
        }
        if (truncationNote != null) {
            contentHeight += LINE + 3;
        }
        scroll = ScrollMath.clampScroll(scroll, contentHeight, listBottom - listTop);

        List<ReputationNetwork.CommunitySummary> communities = ClientReputationData.communities();
        if (canCycleCommunities()) {
            int arrowY = panelTop + panelHeight - 22;
            addRenderableWidget(SpriteButton.arrow(panelLeft + PADDING, arrowY,
                    ARROW_WIDTH, BUTTON_HEIGHT,
                    Component.translatable("mcareputation.screen.previous_community"), true,
                    button -> cycleCommunity(-1)));
            addRenderableWidget(SpriteButton.arrow(panelLeft + PADDING + ARROW_WIDTH + ARROW_GAP,
                    arrowY, ARROW_WIDTH, BUTTON_HEIGHT,
                    Component.translatable("mcareputation.screen.next_community"), false,
                    button -> cycleCommunity(1)));
        }

        int closeWidth = Math.min(80, panelWidth / 3);
        addRenderableWidget(Button.builder(CommonComponentsCompat.done(), button -> onClose())
                .bounds(panelLeft + panelWidth - PADDING - closeWidth, panelTop + panelHeight - 22,
                        closeWidth, BUTTON_HEIGHT)
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

    /**
     * Keeps the selector index pointing at whatever the server actually sent as selected, or at
     * {@link SelectorMath#NOT_IN_LIST} when the selection is a community the player has no record for.
     *
     * <p>That case is real and used to be silently rounded to index {@code 0}: the server details the
     * village you are standing in even when you are a stranger there, and that community is not one of
     * the summaries. Pretending it was the first summary made the arrows skip an entry and made
     * {@link #canCycleCommunities()} answer the wrong question.
     */
    private void syncSelectedIndex() {
        Optional<ReputationNetwork.SelectedDetail> detail = ClientReputationData.selected();
        List<ReputationNetwork.CommunitySummary> communities = ClientReputationData.communities();
        selectedCommunityIndex = SelectorMath.NOT_IN_LIST;
        if (detail.isPresent()) {
            for (int i = 0; i < communities.size(); i++) {
                if (communities.get(i).key().equals(detail.get().key())) {
                    selectedCommunityIndex = i;
                    return;
                }
            }
        }
    }

    /** True when there is somewhere else to go — including back from an off-list selection. */
    private boolean canCycleCommunities() {
        return SelectorMath.canCycle(ClientReputationData.communities().size(),
                selectedCommunityIndex != SelectorMath.NOT_IN_LIST);
    }

    private void cycleCommunity(int direction) {
        List<ReputationNetwork.CommunitySummary> communities = ClientReputationData.communities();
        if (communities.isEmpty()) {
            return;
        }
        selectedCommunityIndex =
                SelectorMath.nextIndex(communities.size(), selectedCommunityIndex, direction);
        scroll = 0;
        ClientReputationData.requestSelected(communities.get(selectedCommunityIndex).key());
    }

    /** Called when a fresh snapshot lands, so the layout follows the new content. */
    void onDataRefreshed() {
        syncSelectedIndex();
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- layout

    /** Wraps the header once, in the order it is drawn. {@link #renderHeader} walks this list. */
    private void layoutHeader(ReputationNetwork.SelectedDetail detail, int textWidth) {
        Component communityName = detail.name().isEmpty()
                ? Component.translatable("mcareputation.community.unnamed", detail.key().villageId())
                : Component.literal(detail.name());
        headerLines.add(new HeaderLine(
                communityName.copy().withStyle(ChatFormatting.BOLD).getVisualOrderText(),
                GuiPalette.TEXT, NAME_GAP));

        // The dimension only earns a line when it is not the overworld, where it would be noise.
        String dimension = detail.key().dimension().toString();
        if (!"minecraft:overworld".equals(dimension)) {
            headerLines.add(new HeaderLine(Component.literal(dimension).getVisualOrderText(),
                    GuiPalette.TEXT_MUTED, LINE));
        }

        Component tierLine = McaReputationConfig.showExactScore()
                ? Component.translatable("mcareputation.screen.tier_with_score", detail.tierName(),
                        detail.score())
                : Component.translatable("mcareputation.screen.tier", detail.tierName());
        headerLines.add(new HeaderLine(tierLine.getVisualOrderText(), GuiPalette.TEXT, TIER_GAP));

        // The tier's authored description — one quiet line of what this standing means here.
        if (detail.tierDescription().isPresent()) {
            List<FormattedCharSequence> description =
                    font.split(detail.tierDescription().get(), textWidth);
            headerLines.add(new HeaderLine(description.isEmpty() ? null : description.get(0),
                    GuiPalette.TEXT_MUTED, LINE));
        }

        // What the one villager the player is looking at makes of them, when the server sent it.
        opinionLine(detail.opinion(), McaReputationConfig.showVillagerOpinion()).ifPresent(line ->
                headerLines.add(new HeaderLine(line.getVisualOrderText(), GuiPalette.TEXT_MUTED, LINE)));

        headerLines.add(new HeaderLine(null, 0, PROGRESS_GAP));

        if (!detail.titles().isEmpty() || !ClientReputationData.globalTitles().isEmpty()) {
            Component titles = titleLine(detail.titles(), ClientReputationData.globalTitles());
            List<FormattedCharSequence> lines = font.split(titles, textWidth);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                headerLines.add(new HeaderLine(lines.get(i), GuiPalette.TEXT, LINE));
            }
        }
    }

    /**
     * The villager's own view of the player, or nothing at all.
     *
     * <p>Pure and static so the two ways it disappears — the player turned it off, or the server sent
     * no opinion because the screen was not opened from a villager — are checkable without a screen.
     * A villager who has heard nothing still gets a line: "nobody here knows you" is an answer.
     */
    static Optional<Component> opinionLine(Optional<ReputationNetwork.OpinionSummary> opinion,
                                           boolean show) {
        if (!show || opinion.isEmpty()) {
            return Optional.empty();
        }
        ReputationNetwork.OpinionSummary summary = opinion.get();
        if (summary.basis() == VillagerOpinion.OpinionBasis.NONE) {
            return Optional.of(Component.translatable("mcareputation.screen.opinion.none",
                    summary.villagerName()));
        }
        return Optional.of(Component.translatable("mcareputation.screen.opinion", summary.villagerName(),
                summary.tierName(), Component.translatable(summary.basis().translationKey())));
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

    /** Wraps the ledger once. {@link #renderDeeds} walks this list at the same row heights. */
    private void layoutDeeds(ReputationNetwork.SelectedDetail detail) {
        for (ReputationNetwork.IncidentSummary incident : detail.incidents()) {
            deedLines.add(new DeedLine(font.split(incident.display(), deedWidth),
                    metaLine(incident).getVisualOrderText()));
        }
        if (detail.incidents().size() < detail.totalIncidents()) {
            // §27.3 bounds the packet; the player must be able to tell "that's all" from "that's
            // all that fits" — a silently truncated ledger reads as a shorter life than they led.
            truncationNote = Component.translatable("mcareputation.screen.deeds_truncated",
                    detail.incidents().size(), detail.totalIncidents()).getVisualOrderText();
        }
    }

    /** The meta line carries sign and words, never colour alone (§28.4). */
    private static Component metaLine(ReputationNetwork.IncidentSummary incident) {
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
        return meta;
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

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        GuiTextures.panel(graphics, panelLeft, panelTop, panelWidth, panelHeight);

        Optional<ReputationNetwork.SelectedDetail> detail = ClientReputationData.selected();
        if (detail.isEmpty()) {
            renderEmptyState(graphics);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        renderHeader(graphics, detail.get());
        renderDeeds(graphics);
        renderFooter(graphics);
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
            graphics.drawString(font, line, panelLeft + PADDING, y, GuiPalette.TEXT_MUTED, false);
            y += LINE;
        }
    }

    private void renderHeader(GuiGraphics graphics, ReputationNetwork.SelectedDetail detail) {
        int x = panelLeft + PADDING;
        int y = panelTop + HEADER_TOP;
        for (HeaderLine line : headerLines) {
            if (line.text() == null) {
                renderProgress(graphics, detail, x, y, panelWidth - 2 * PADDING);
            } else {
                graphics.drawString(font, line.text(), x, y, line.colour(), false);
            }
            y += line.height();
        }
    }

    private void renderProgress(GuiGraphics graphics, ReputationNetwork.SelectedDetail detail,
                                int x, int y, int barWidth) {
        boolean atTop = detail.nextTierId().isEmpty();
        float progress = atTop ? 1.0f
                : ReputationMath.progress(detail.score(), detail.tierThreshold(), detail.nextThreshold());
        GuiTextures.progressTrack(graphics, x, y, barWidth);
        GuiTextures.progressFill(graphics, x + 1, y + 1, Math.round((barWidth - 2) * progress));

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
        graphics.drawString(font, caption, x, y + GuiTextures.PROGRESS_HEIGHT + 1,
                GuiPalette.TEXT_MUTED, false);
    }

    private void renderDeeds(GuiGraphics graphics) {
        // Hung off the well rather than the header, so they travel with it — and dropped outright
        // when the well has had to climb into the space they would occupy. A ledger with no heading
        // is a smaller loss at a punishing GUI scale than a heading written over the tier line.
        if (wellTop - LABEL_BLOCK >= headerBottom) {
            GuiTextures.separator(graphics, panelLeft + PADDING, wellTop - LABEL_BLOCK,
                    panelWidth - 2 * PADDING);
            graphics.drawString(font, Component.translatable("mcareputation.screen.deeds"),
                    panelLeft + PADDING, wellTop - LINE - 1, GuiPalette.TEXT, false);
        }

        if (!hasWell) {
            return;
        }
        GuiTextures.well(graphics, wellLeft, wellTop, wellRight - wellLeft, wellBottom - wellTop);

        if (deedLines.isEmpty() && truncationNote == null) {
            // Nothing to scroll, so no channel is reserved and the message gets the whole well.
            List<FormattedCharSequence> empty =
                    font.split(Component.translatable("mcareputation.screen.no_deeds"),
                            wellRight - 3 - deedLeft);
            int y = listTop + 2;
            for (FormattedCharSequence line : empty) {
                graphics.drawString(font, line, deedLeft, y, GuiPalette.TEXT_MUTED, false);
                y += LINE;
            }
            return;
        }

        // Scissor to the list box so a long ledger can never draw over the header or the buttons,
        // however small the GUI scale is.
        graphics.enableScissor(wellLeft + 1, listTop, wellRight - 1, listBottom);
        int y = listTop - (int) scroll;
        for (DeedLine deed : deedLines) {
            int used = 0;
            for (FormattedCharSequence line : deed.body()) {
                graphics.drawString(font, line, deedLeft, y + used, GuiPalette.TEXT, false);
                used += LINE;
            }
            graphics.drawString(font, deed.meta(), deedLeft + 4, y + used,
                    GuiPalette.TEXT_MUTED, false);
            y += deed.height() + 3;
        }
        if (truncationNote != null) {
            graphics.drawString(font, truncationNote, deedLeft, y, GuiPalette.TEXT_MUTED, false);
        }
        graphics.disableScissor();

        renderScrollbar(graphics);
    }

    /**
     * The channel is drawn whenever there is a well, the scroller only when there is somewhere to
     * scroll — which is also what tells the player at a glance that the ledger is complete.
     */
    private void renderScrollbar(GuiGraphics graphics) {
        int trackHeight = listBottom - listTop;
        GuiTextures.scrollGroove(graphics, grooveX, listTop, trackHeight);
        if (contentHeight <= trackHeight) {
            return;
        }
        GuiTextures.scrollThumb(graphics, grooveX + 1,
                listTop + ScrollMath.thumbY(scroll, contentHeight, trackHeight),
                ScrollMath.thumbHeight(contentHeight, trackHeight));
    }

    private void renderFooter(GuiGraphics graphics) {
        List<ReputationNetwork.CommunitySummary> communities = ClientReputationData.communities();
        if (communities.size() <= 1) {
            return;
        }
        Component label = Component.translatable("mcareputation.screen.community_index",
                selectedCommunityIndex + 1, communities.size());
        // Clear of the two selector arrows, measured from their bounds rather than guessed.
        int x = panelLeft + PADDING + 2 * ARROW_WIDTH + ARROW_GAP + 6;
        graphics.drawString(font, label, x, panelTop + panelHeight - 17,
                GuiPalette.TEXT_MUTED, false);
    }

    // ----------------------------------------------------------------- input

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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int trackHeight = listBottom - listTop;
        if (button == 0 && hasWell && contentHeight > trackHeight
                && mouseX >= grooveX && mouseX < grooveX + SCROLL_CHANNEL
                && mouseY >= listTop && mouseY < listBottom) {
            int thumbHeight = ScrollMath.thumbHeight(contentHeight, trackHeight);
            int thumbTop = listTop + ScrollMath.thumbY(scroll, contentHeight, trackHeight);
            if (mouseY < thumbTop || mouseY >= thumbTop + thumbHeight) {
                // A click on the bare channel takes the scroller to the pointer, as vanilla does.
                scroll = ScrollMath.scrollForThumbTop(mouseY - listTop - thumbHeight / 2.0,
                        contentHeight, trackHeight);
                thumbTop = listTop + ScrollMath.thumbY(scroll, contentHeight, trackHeight);
            }
            dragOffset = mouseY - thumbTop;
            draggingScroll = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll) {
            scroll = ScrollMath.scrollForThumbTop(mouseY - dragOffset - listTop, contentHeight,
                    listBottom - listTop);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            draggingScroll = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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
