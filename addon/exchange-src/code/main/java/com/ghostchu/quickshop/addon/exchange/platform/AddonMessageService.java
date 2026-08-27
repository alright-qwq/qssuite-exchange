package com.ghostchu.quickshop.addon.exchange.platform;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Locale lookup with an English fallback for addon-facing messages. */
public final class AddonMessageService {
  private final Map<String, Map<String, String>> messages;

  public AddonMessageService(Map<String, Map<String, String>> messages) {
    this.messages = Map.copyOf(messages);
  }

  public static AddonMessageService load(File source) {
    Objects.requireNonNull(source, "source");
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(source);
    Map<String, Map<String, String>> localized = new LinkedHashMap<>();
    for (String locale : yaml.getKeys(false)) {
      ConfigurationSection section = yaml.getConfigurationSection(locale);
      if (section == null) {
        continue;
      }
      Map<String, String> entries = new LinkedHashMap<>();
      for (String key : section.getKeys(false)) {
        String value = section.getString(key);
        if (value != null) {
          entries.put(key, value);
        }
      }
      localized.put(locale, Map.copyOf(entries));
    }
    if (!localized.containsKey("en-US")) {
      throw new IllegalArgumentException("messages.yml must define en-US");
    }
    return new AddonMessageService(localized);
  }

  public String message(String key, Locale locale, Object... arguments) {
    Map<String, String> localized = messages.getOrDefault(locale.toLanguageTag(), messages.get("en-US"));
    String template = Objects.requireNonNull(localized, "missing en-US messages").get(key);
    if (template == null) template = messages.get("en-US").getOrDefault(key, key);
    for (int index = 0; index < arguments.length; index++) {
      template = template.replace("<" + index + ">", String.valueOf(arguments[index]));
    }
    if (arguments.length > 0) {
      template = template.replace("<requestId>", String.valueOf(arguments[0]));
    }
    return template;
  }
}
