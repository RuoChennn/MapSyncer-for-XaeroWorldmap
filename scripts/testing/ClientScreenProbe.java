import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import java.util.*;

/** Read-only JDI observation: no method invocation, commands or field writes. */
public final class ClientScreenProbe {
    static String quote(String s) {
        StringBuilder result=new StringBuilder("\"");
        for(char ch:s.toCharArray()) {
            if(ch=='\\' || ch=='\"') result.append('\\').append(ch);
            else if(ch<32) result.append(String.format("\\u%04x",(int)ch));
            else result.append(ch);
        }
        return result.append('"').toString();
    }
    static String text(Value v, int depth, Set<Long> seen) {
        if (v == null) return "null";
        if (v instanceof StringReference s) return quote(s.value());
        if (!(v instanceof ObjectReference o)) return v.toString();
        String type=o.referenceType().name();
        if (depth==0 || !seen.add(o.uniqueID())) return quote(type);
        if (o instanceof ArrayReference a) {
            List<String> values=new ArrayList<>();
            for (Value item:a.getValues(0,Math.min(a.length(),30))) if(item!=null) values.add(text(item,depth-1,seen));
            return "["+String.join(",",values)+"]";
        }
        if (type.startsWith("java.util.ArrayList")) {
            return text(o.getValue(o.referenceType().fieldByName("elementData")),depth-1,seen);
        }
        if (type.startsWith("com.google.common.collect.") && type.contains("ImmutableList")) {
            for(Field f:o.referenceType().allFields()) if(!f.isStatic() && (f.name().equals("array") || f.name().equals("element")))
                return text(o.getValue(f),depth-1,seen);
        }
        List<String> values=new ArrayList<>(); values.add("\"type\":"+quote(type));
        for (Field f:o.referenceType().allFields()) {
            if (f.isStatic()) continue;
            Value x=o.getValue(f);
            String ft=f.typeName();
            boolean relevant=x instanceof StringReference || x instanceof BooleanValue
                || ft.contains("Component") || ft.contains("Content") || ft.contains("class_7417") || ft.contains("class_2561") || ft.contains("class_5250")
                || ft.contains("class_2585") || ft.contains("class_2588") || ft.contains("GuiEventListener") || ft.contains("class_364")
                || ft.contains("EditBox") || ft.contains("class_342") || ft.equals("java.util.List")
                || ft.contains("Button") || ft.contains("class_4185") || ft.contains("GuiMapSwitching")
                || ft.contains("Dropdown");
            if (relevant && x!=null) values.add(quote(f.name())+":"+text(x,depth-1,
                ft.contains("GuiEventListener") || ft.contains("class_364") ? new HashSet<>() : seen));
        }
        return "{"+String.join(",",values)+"}";
    }
    public static void main(String[] args) throws Exception {
        AttachingConnector connector=Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options=connector.defaultArguments();options.get("hostname").setValue("127.0.0.1");options.get("port").setValue(args[0]);
        options.get("timeout").setValue("5000");
        VirtualMachine vm=connector.attach(options);
        try {
            ReferenceType mc=null;
            for(String n:List.of("net.minecraft.client.Minecraft","net.minecraft.class_310")) {
                var types=vm.classesByName(n); if(!types.isEmpty()) {mc=types.getFirst();break;}
            }
            if(mc==null) {System.out.println("{\"state\":\"loading\"}");return;}
            ObjectReference instance=null;
            for(Field f:mc.allFields()) if(f.isStatic() && f.typeName().equals(mc.name()) && mc.getValue(f) instanceof ObjectReference o) {instance=o;break;}
            if(instance==null) {System.out.println("{\"state\":\"initializing\"}");return;}
            ObjectReference screen=null;
            for(Field f:mc.allFields()) if(!f.isStatic() && List.of("net.minecraft.client.gui.screens.Screen","net.minecraft.class_437").contains(f.typeName())) {
                Value value=instance.getValue(f);if(value instanceof ObjectReference o) screen=o;break;
            }
            boolean playerPresent=false, levelPresent=false;
            for(Field f:mc.allFields()) if(!f.isStatic()) {
                if(List.of("net.minecraft.client.player.LocalPlayer","net.minecraft.class_746").contains(f.typeName()))
                    playerPresent=instance.getValue(f)!=null;
                if(List.of("net.minecraft.client.multiplayer.ClientLevel","net.minecraft.class_638").contains(f.typeName()))
                    levelPresent=instance.getValue(f)!=null;
            }
            System.out.println("{\"playerPresent\":"+playerPresent+",\"levelPresent\":"+levelPresent
                +",\"screen\":"+text(screen,8,new HashSet<>())+"}");
        } finally {vm.dispose();}
    }
}

