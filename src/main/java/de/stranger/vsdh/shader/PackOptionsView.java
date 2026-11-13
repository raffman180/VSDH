package de.stranger.vsdh.shader;

import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.PackOption;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.PackLang;
import dev.vitrail.pack.source.ShaderProperties.ScreenToken;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settings a pack offers, as its own option screens lay them out (shaders.properties
 * {@code screen=}), with the names its language file gives them. Read on the preparation worker
 * from the pack being loaded, then shown by the Pack options page. Like Iris, only options the
 * pack's screens reach are listed, and a value is either on/off or one of the listed values.
 */
public record PackOptionsView(String pack, String profile, List<Block> blocks) {
    /** {@code base}: the value of the profile, or the pack default; {@code current}: what is set now. */
    public record Entry(String name, String label, String tooltip, boolean toggle, List<String> values,
                        List<String> valueLabels, String base, String current) {}

    public record Block(String title, List<Entry> entries) {}

    private static volatile PackOptionsView latest;

    /** The view of the pack prepared last, or null. */
    public static PackOptionsView latest() {
        return latest;
    }

    static void remember(PackOptionsView view) {
        latest = view;
    }

    public static PackOptionsView read(OpenedPack pack, String packName, String profile, String languageCode) {
        PackLang read;
        try {
            read = PackLang.read(pack.source(), languageCode);
        } catch (java.io.IOException | RuntimeException unreadable) {
            read = PackLang.empty();
        }
        final PackLang lang = read;
        Map<String, OptionValue> fromProfile = profile.isEmpty() ? Map.of() : pack.properties().expandProfile(profile);
        Map<String, OptionValue> chosen = pack.settings().chosen();
        var offered = new LinkedHashMap<String, PackOption>();
        for (PackOption option : pack.options().all()) {
            if (!pack.options().offers(option)) continue;
            if (option.kind() == PackOption.Kind.TOGGLE || option.hasValueList()) offered.put(option.name(), option);
        }

        var blocks = new ArrayList<Block>();
        var placed = new HashSet<String>();
        Map<String, List<ScreenToken>> layout = pack.properties().screenLayout();
        if (layout.isEmpty()) {
            var all = new ArrayList<Entry>();
            offered.keySet().stream().sorted().forEach(name -> all.add(entry(offered.get(name), lang, fromProfile, chosen)));
            if (!all.isEmpty()) blocks.add(new Block("", all));
        } else {
            walk("", "", layout, offered, lang, fromProfile, chosen, placed, new HashSet<>(), blocks);
        }
        return new PackOptionsView(packName, profile, List.copyOf(blocks));
    }

    private static void walk(String page, String title, Map<String, List<ScreenToken>> layout,
                             Map<String, PackOption> offered, PackLang lang, Map<String, OptionValue> fromProfile,
                             Map<String, OptionValue> chosen, Set<String> placed, Set<String> visited, List<Block> blocks) {
        if (!visited.add(page)) return;
        var tokens = layout.getOrDefault(page, List.of());
        var entries = new ArrayList<Entry>();
        var links = new ArrayList<String>();
        for (ScreenToken token : tokens) {
            switch (token) {
                case ScreenToken.Name name -> {
                    PackOption option = offered.get(name.name());
                    if (option != null && placed.add(option.name())) entries.add(entry(option, lang, fromProfile, chosen));
                }
                case ScreenToken.Link link -> links.add(link.page());
                case ScreenToken.Rest rest -> offered.keySet().stream().sorted()
                        .filter(placed::add)
                        .forEach(name -> entries.add(entry(offered.get(name), lang, fromProfile, chosen)));
                case ScreenToken.Blank blank -> { }
                case ScreenToken.Profiles profiles -> { }
            }
        }
        if (!entries.isEmpty()) blocks.add(new Block(title, List.copyOf(entries)));
        for (String link : links)
            walk(link, lang.page(link), layout, offered, lang, fromProfile, chosen, placed, visited, blocks);
    }

    private static Entry entry(PackOption option, PackLang lang, Map<String, OptionValue> fromProfile,
                               Map<String, OptionValue> chosen) {
        boolean toggle = option.kind() == PackOption.Kind.TOGGLE;
        String fallback = toggle ? Boolean.toString(!option.defaultOff()) : option.defaultText().strip();
        String base = text(fromProfile.get(option.name()), toggle, fallback);
        String current = text(chosen.get(option.name()), toggle, base);
        var values = new ArrayList<String>(toggle ? List.of("true", "false") : option.values());
        // A default the pack does not list (or a profile value outside the list) is still selectable.
        for (String needed : List.of(base, current)) if (!values.contains(needed)) values.add(needed);
        var labels = new ArrayList<String>();
        for (String value : values) labels.add(toggle ? value : lang.value(option.name(), value));
        return new Entry(option.name(), lang.option(option.name()), lang.optionComment(option.name()).orElse(null),
                toggle, List.copyOf(values), List.copyOf(labels), base, current);
    }

    private static String text(OptionValue value, boolean toggle, String fallback) {
        if (value == null) return fallback;
        if (toggle) return Boolean.toString(value.isBoolean() ? value.asBoolean() : !value.text().equals("0"));
        return value.text().strip();
    }
}
