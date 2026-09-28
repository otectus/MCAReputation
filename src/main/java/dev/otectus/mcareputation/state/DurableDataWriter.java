package dev.otectus.mcareputation.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class DurableDataWriter {
    private DurableDataWriter() {}

    static void write(File destination, CompoundTag data) throws IOException {
        File parent=destination.getAbsoluteFile().getParentFile();
        if(parent!=null)Files.createDirectories(parent.toPath());
        File temporary=File.createTempFile(destination.getName(),".tmp",parent);boolean moved=false;
        try {
            CompoundTag root=new CompoundTag();root.put("data",data);NbtUtils.addCurrentDataVersion(root);
            NbtIo.writeCompressed(root,temporary);
            try(FileChannel channel=FileChannel.open(temporary.toPath(),StandardOpenOption.WRITE)){channel.force(true);}
            try{Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            moved=true;
            if(parent!=null)try(FileChannel directory=FileChannel.open(parent.toPath(),StandardOpenOption.READ)){directory.force(true);}catch(IOException ignored){}
        } finally {if(!moved)Files.deleteIfExists(temporary.toPath());}
    }
}
