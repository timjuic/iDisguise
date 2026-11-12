package de.luisagrether.idisguise.player;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Swaps a disguised player's real name for the fake one in texts.
 * Whole words only (so "Ana" never changes "Banana"), any capitalisation,
 * and a colour code right before the name still counts as a word start ("§7Dany").
 */
final class NameReplacer {

	private NameReplacer() {}

	/**
	 * Put inside a disguised player's real name when another player types it in a command, so the
	 * command acts as if that player is offline. Removed again from everything sent to players, so it
	 * is never seen (some clients would draw it as a box). Zero-width space + word joiner: a pair no
	 * other plugin uses, so only this marker is ever removed.
	 */
	static final String MARKER = "​⁠";

	static String mark(String name) {
		return name.charAt(0) + MARKER + name.substring(1);
	}

	/** Marks every real name of a disguised player in text a player typed, so the name swap leaves it as typed. */
	static String markTyped(String text, Collection<PlayerDisguise> disguises) {
		if(!mentionsAny(text, disguises)) return text;
		String result = text;
		for(PlayerDisguise disguise : disguises) {
			Matcher matcher = disguise.getRealNamePattern().matcher(result);
			StringBuffer out = new StringBuffer();
			while(matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(mark(matcher.group())));
			matcher.appendTail(out);
			result = out.toString();
		}
		return result;
	}

	static Pattern wordPattern(String name) {
		return Pattern.compile("(?:(?<=§[0-9a-fk-orA-FK-OR])|(?<![A-Za-z0-9_]))" + Pattern.quote(name) + "(?![A-Za-z0-9_])",
				Pattern.CASE_INSENSITIVE);
	}

	/** Cheap check before doing any real work. */
	static boolean mentionsAny(String text, Collection<PlayerDisguise> disguises) {
		if(text == null || text.isEmpty()) return false;
		if(text.contains(MARKER)) return true;
		String lower = text.toLowerCase(Locale.ENGLISH);
		for(PlayerDisguise disguise : disguises) {
			if(lower.contains(disguise.getRealName().toLowerCase(Locale.ENGLISH))) return true;
		}
		return false;
	}

	static String replace(String text, Collection<PlayerDisguise> disguises) {
		if(!mentionsAny(text, disguises)) return text;
		// Two steps, so a fake name that is someone else's real name isn't swapped a second time:
		// first every real name becomes a token no name can match, then the tokens become fake names.
		String result = text;
		java.util.List<String> fakes = new java.util.ArrayList<>();
		for(PlayerDisguise disguise : disguises) {
			Matcher matcher = disguise.getRealNamePattern().matcher(result);
			if(!matcher.find()) continue;
			String token = TOKEN_START + fakes.size() + TOKEN_END;
			fakes.add(disguise.getFakeName());
			result = matcher.replaceAll(Matcher.quoteReplacement(token));
		}
		for(int i = 0; i < fakes.size(); i++) {
			result = result.replace(TOKEN_START + i + TOKEN_END, fakes.get(i));
		}
		// After the swap: a marked real name was typed by the player, it stays (and looks) as typed.
		return result.replace(MARKER, "");
	}

	// Private-use characters: never in names, never sent (every token is replaced right away).
	private static final String TOKEN_START = "\uE000";
	private static final String TOKEN_END = "\uE001";

	/**
	 * Replaces names inside a chat component (JSON). Only text values are touched, never keys,
	 * colours or translation keys, so the JSON stays valid even for a player called "text".
	 * Returns null when nothing changed.
	 */
	@SuppressWarnings("deprecation")
	static String replaceJson(String json, Collection<PlayerDisguise> disguises) {
		if(!mentionsAny(json, disguises)) return null;
		JsonElement root;
		try {
			root = new JsonParser().parse(json);
		} catch(RuntimeException e) {
			return null;
		}
		JsonElement replaced = walk(root, null, disguises);
		String result = replaced.toString();
		return result.equals(json) ? null : result;
	}

	private static JsonElement walk(JsonElement element, String key, Collection<PlayerDisguise> disguises) {
		if(element == null || element.isJsonNull()) return element;
		if(element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if(!primitive.isString() || isFixedKey(key)) return element;
			String value = primitive.getAsString();
			String replaced = replace(value, disguises);
			// A hover over a name (death messages: show_entity) also carries the player's real ID.
			if("value".equals(key)) replaced = replaceIds(replaced, disguises);
			return replaced.equals(value) ? element : new JsonPrimitive(replaced);
		}
		if(element.isJsonArray()) {
			JsonArray result = new JsonArray();
			for(JsonElement child : element.getAsJsonArray()) {
				result.add(walk(child, key, disguises));
			}
			return result;
		}
		JsonObject result = new JsonObject();
		for(Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
			result.add(entry.getKey(), walk(entry.getValue(), entry.getKey(), disguises));
		}
		return result;
	}

	/** Real player IDs in a hover value become the IDs the disguises are shown with. */
	private static String replaceIds(String text, Collection<PlayerDisguise> disguises) {
		String result = text;
		for(PlayerDisguise disguise : disguises) {
			String real = disguise.getUniqueId().toString();
			if(result.toLowerCase(Locale.ENGLISH).contains(real)) {
				result = Pattern.compile(Pattern.quote(real), Pattern.CASE_INSENSITIVE).matcher(result)
						.replaceAll(Matcher.quoteReplacement(disguise.getFakeUniqueId().toString()));
			}
		}
		return result;
	}

	private static boolean isFixedKey(String key) {
		return "color".equals(key) || "translate".equals(key) || "action".equals(key) || "font".equals(key);
	}

}
