package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** No Minecraft world or real account: only a copy of an event and the isolated API. */
public final class OutboxHttpDeliveryFixture {
    private OutboxHttpDeliveryFixture() {}
    public static void main(String[] arguments)throws Exception{
        if(arguments.length!=0)throw new IllegalArgumentException("Environment-only fixture");
        var directory=Path.of(System.getenv("AOC_TEST_OUTBOX_DIRECTORY")).toRealPath();
        if(!directory.getFileName().toString().matches("outbox-delivery-wire-[A-Za-z0-9]+")
                ||!directory.getParent().getFileName().toString().equals(".tools"))throw new IllegalArgumentException("Disposable outbox required");
        var endpoint=URI.create(System.getenv("AOC_TEST_OUTBOX_ENDPOINT"));
        if(!endpoint.getScheme().equals("http")||!endpoint.getHost().equals("127.0.0.1")||endpoint.getPort()<1024
                ||endpoint.getRawUserInfo()!=null||endpoint.getRawQuery()!=null
                ||!endpoint.getPath().equals("/integration/v1/competitions/world-boss/result"))throw new IllegalArgumentException("Isolated loopback API required");
        String key=System.getenv("AOC_TEST_OUTBOX_KEY");
        if(key==null||!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Fixture credential missing");
        // JavaExec uses compiled main classes: ensure they are the exact packaged implementation.
        try(var jar=new ZipFile(System.getenv("AOC_TEST_ADMISSION_JAR"))){
            for(String name:new String[]{"GameplayOutbox","HttpJson","HttpJson$LimitedBody","HttpJson$LauncherUpdateRequired"}){
                String entry="ru/animeoncraft/integration/neoforge/"+name+".class";
                try(var loaded=OutboxHttpDeliveryFixture.class.getResourceAsStream("/"+entry);
                    var packaged=jar.getInputStream(jar.getEntry(entry))){
                    if(loaded==null||!Arrays.equals(loaded.readAllBytes(),packaged.readAllBytes()))throw new IllegalStateException("Packaged delivery code differs");
                }
            }
        }
        Path event;
        try(var files=Files.list(directory)){var events=files.filter(file->file.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).toList();
            if(events.size()!=1)throw new IllegalStateException("One copied event required");event=events.getFirst();}
        String original=Files.readString(event);
        var body=JsonParser.parseString(original).getAsJsonObject();
        if(!event.getFileName().toString().equals(UUID.fromString(body.get("event_id").getAsString())+".json"))throw new IllegalStateException("Event filename differs");
        String wrongKey=(key.charAt(0)=='a'?"b":"a")+key.substring(1);
        if(GameplayOutbox.deliver(event,value->HttpJson.post(endpoint,wrongKey,value)).get(10,TimeUnit.SECONDS)||!Files.exists(event))throw new IllegalStateException("Rejected HTTP deleted event");
        var wrongAck=endpoint.resolve("/fixture/wrong-ack");
        if(GameplayOutbox.deliver(event,value->HttpJson.post(wrongAck,key,value)).get(10,TimeUnit.SECONDS)||!Files.exists(event))throw new IllegalStateException("Wrong HTTP acknowledgement deleted event");
        if(!GameplayOutbox.deliver(event,value->HttpJson.post(endpoint,key,value)).get(10,TimeUnit.SECONDS)||Files.exists(event))throw new IllegalStateException("Confirmed HTTP did not remove event");
        Files.writeString(event,original);
        if(!GameplayOutbox.deliver(event,value->HttpJson.post(endpoint,key,value)).get(10,TimeUnit.SECONDS)||Files.exists(event))throw new IllegalStateException("Duplicate confirmation did not remove event");
        System.out.println("AOC_OUTBOX_HTTP_DELIVERY_PASS rejected_retained=true wrong_ack_retained=true confirmed_deleted=true duplicate_deleted=true packaged_classes_equal=true");
    }
}
