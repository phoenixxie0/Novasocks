package com.github.novasocks;

import android.content.Context;

public class ImageLoaderFactory {

    private static ImageLoader il = null;

    public static ImageLoader getImageLoader(Context context) {
        if (il == null) {
            il = new ImageLoader(context);
        }
        return il;
    }
}
