package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.PropertiesFile;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Pure item.properties parsing. Unregistered/future items do not become invented registry entries. */
public final class ShaderItemMaterials {
    private static final Pattern ENTRY=Pattern.compile("^\\s*item\\.(-?\\d+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern ID=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private ShaderItemMaterials() {}
    public static Map<String,Integer> read(OpenedPack pack) throws IOException {
        var result=new LinkedHashMap<String,Integer>();
        PropertiesFile.read(pack.source(),"item.properties").walk(pack.settings().globalDefines(pack.options()),line->{
            var match=ENTRY.matcher(line); if(!match.matches())return;
            int material=Integer.parseInt(match.group(1));
            for(String token:match.group(2).split("\\s+")) {
                if(token.isEmpty())continue;
                String id=token.contains(":")?token:"minecraft:"+token;
                if(!ID.matcher(id).matches())continue;
                result.putIfAbsent(id,material);
            }
        });
        return Map.copyOf(result);
    }
}
