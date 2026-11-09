package de.stranger.vsdh.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Conservative access reflection for the compiler's set-0 uniform blocks. Never relies on debug names. */
public final class UniformUsage {
    private UniformUsage() {}
    public record Reads(Set<Integer> offsets, boolean conservative) {
        public Reads { offsets=Set.copyOf(offsets); }
    }

    public static Set<String> names(VulkanProgramCompiler.Compiled compiled, Std140Layout layout) {
        Integer binding=compiled.bindings().get("OfGlobals");
        if(binding==null)return Set.of();
        Set<Integer> offsets=new HashSet<>();
        for(byte[] stage:compiled.stages().values())offsets.addAll(read(stage,0,binding).offsets());
        Map<Integer,String> names=new HashMap<>();
        for(var member:layout.members())names.put(member.offset(),member.name());
        Set<String> active=new TreeSet<>();
        for(int offset:offsets) {
            String name=names.get(offset);
            if(name==null)throw new IllegalArgumentException("SPIR-V uniform offset is absent from host layout: "+offset);
            active.add(name);
        }
        return Set.copyOf(active);
    }

    /** Byte offsets survive optimiser removal/renumbering of unused struct members; member indexes do not. */
    public static Reads read(byte[] spirv,int descriptorSet,int binding) {
        SpirvInterface.bindings(spirv); // Validate header and every instruction boundary first.
        ByteBuffer words=ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        Map<Integer,Integer> sets=new HashMap<>(),bindings=new HashMap<>(),constants=new HashMap<>();
        Map<Integer,int[]> pointers=new HashMap<>(),variables=new HashMap<>();
        Map<Integer,Integer> structs=new HashMap<>();
        Map<Integer,Map<Integer,Integer>> offsets=new HashMap<>();
        for(int at=20;at<words.limit();) {
            int instruction=words.getInt(at),count=instruction>>>16,op=instruction&65535;
            switch(op) {
                case 30 -> {if(count>=2)structs.put(words.getInt(at+4),count-2);} // OpTypeStruct
                case 32 -> {if(count==4)pointers.put(words.getInt(at+4),new int[]{words.getInt(at+8),words.getInt(at+12)});}
                case 43 -> {if(count==4)constants.put(words.getInt(at+8),words.getInt(at+12));}
                case 59 -> {if(count>=4)variables.put(words.getInt(at+8),new int[]{words.getInt(at+4),words.getInt(at+12)});}
                case 71 -> {
                    if(count>=4 && words.getInt(at+8)==33)bindings.put(words.getInt(at+4),words.getInt(at+12));
                    if(count>=4 && words.getInt(at+8)==34)sets.put(words.getInt(at+4),words.getInt(at+12));
                }
                case 72 -> { // OpMemberDecorate Offset
                    if(count>=5 && words.getInt(at+12)==35)
                        offsets.computeIfAbsent(words.getInt(at+4),key->new HashMap<>()).put(words.getInt(at+8),words.getInt(at+16));
                }
                case 73,74,75 -> throw new IllegalArgumentException("Grouped SPIR-V decorations need explicit uniform reflection support");
            }
            at+=count*4;
        }
        int root=0,rootStruct=0;
        for(var variable:variables.entrySet()) {
            int id=variable.getKey();int[] v=variable.getValue();
            if(v[1]!=2 || bindings.getOrDefault(id,-1)!=binding || sets.getOrDefault(id,-1)!=descriptorSet)continue;
            if(root!=0)throw new IllegalArgumentException("Duplicate uniform descriptor binding");
            int[] pointer=pointers.get(v[0]);
            if(pointer==null || pointer[0]!=2 || !structs.containsKey(pointer[1]))
                throw new IllegalArgumentException("Uniform descriptor is not a directly addressed struct");
            root=id;rootStruct=pointer[1];
        }
        if(root==0)return new Reads(Set.of(),false);
        Map<Integer,Integer> members=offsets.getOrDefault(rootStruct,Map.of());
        if(members.size()!=structs.get(rootStruct))throw new IllegalArgumentException("Uniform member offsets are incomplete");
        for(int index=0;index<structs.get(rootStruct);index++)
            if(!members.containsKey(index))throw new IllegalArgumentException("Invalid uniform member index table");
        Set<Integer> all=new HashSet<>(members.values()),read=new HashSet<>();
        if(all.size()!=members.size() || all.stream().anyMatch(i->i<0))throw new IllegalArgumentException("Invalid uniform offsets");
        boolean function=false;
        for(int at=20;at<words.limit();) {
            int instruction=words.getInt(at),count=instruction>>>16,op=instruction&65535;
            if(op==54)function=true;
            if(op==56)function=false;
            if(function) {
                if((op==65 || op==66) && count>=4 && words.getInt(at+12)==root) {
                    if(count<5)return new Reads(all,true); // Pointer to the whole block.
                    Integer index=constants.get(words.getInt(at+16));
                    if(index==null)return new Reads(all,true);
                    Integer offset=members.get(index);
                    if(offset==null)throw new IllegalArgumentException("SPIR-V uniform struct index is out of bounds: "+index);
                    read.add(offset);
                } else {
                    // Aliases, function pointer parameters, physical pointer arithmetic and whole-block
                    // loads are deliberately conservative. No value may be omitted by an unknown path.
                    if(count>=3 && pointers.containsKey(words.getInt(at+4)) && op!=65 && op!=66)
                        if(pointers.get(words.getInt(at+4))[0]==2)return new Reads(all,true);
                    if(op==61 && count>=4 && words.getInt(at+12)==root)return new Reads(all,true);
                    if((op==62 || op==63 || op==64) && count>=3
                            && (words.getInt(at+4)==root || words.getInt(at+8)==root))return new Reads(all,true);
                    if(op==57 && count>=5)for(int i=4;i<count;i++)if(words.getInt(at+i*4)==root)return new Reads(all,true);
                    if((op==67 || op==70) && count>=4 && words.getInt(at+12)==root)return new Reads(all,true);
                }
            }
            at+=count*4;
        }
        return new Reads(read,false);
    }
}
