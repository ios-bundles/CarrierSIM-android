package ru.carriersim.android;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only, individually granted exports. Pair records and internal files are never exposed. */
public class ExportProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException{
        String name=uri.getLastPathSegment();
        if(uri.getPathSegments().size()!=1||name==null||name.contains("/")||name.contains("\\")||name.equals(".")||name.equals(".."))throw new FileNotFoundException();
        File root=new File(getContext().getCacheDir(),"exports"),file=new File(root,name);
        try{if(!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())||!file.isFile())throw new FileNotFoundException();}catch(IOException e){throw new FileNotFoundException();}
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!mode.equals("r"))throw new FileNotFoundException("Read-only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public String getType(Uri uri){return uri.toString().endsWith(".zip")?"application/zip":"text/plain";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{File f=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?f.getName():columns[i].equals(OpenableColumns.SIZE)?f.length():null;cursor.addRow(row);return cursor;}catch(FileNotFoundException e){return null;}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
