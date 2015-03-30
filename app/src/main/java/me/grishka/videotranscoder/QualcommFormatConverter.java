package me.grishka.videotranscoder;

/**
 * Created by grishka on 20.01.15.
 * Ported to Java from https://github.com/videolan/vlc/blob/master/modules/codec/omxil/qcom.c
 */
public class QualcommFormatConverter {
	/*
 * The format is called QOMX_COLOR_FormatYUV420PackedSemiPlanar64x32Tile2m8ka.
 * First wtf: why call it YUV420? It is NV12 (interleaved U&V).
 * Second wtf: why this format at all?
 */
	private static final int TILE_WIDTH=64;
	private static final int TILE_HEIGHT=32;
	private static final int TILE_SIZE=(TILE_WIDTH * TILE_HEIGHT);
	private static final int TILE_GROUP_SIZE=(4 * TILE_SIZE);

	/* get frame tile coordinate. XXX: nothing to be understood here, don't try. */
	private static int tile_pos(int x, int y, int w, int h)
	{
		int flim = x + (y & ~1) * w;

		if ((y & 1)>0) {
			flim += (x & ~3) + 2;
		} else if ((h & 1) == 0 || y != (h - 1)) {
			flim += (x + 2) & ~3;
		}

		if(flim<0) flim&=Integer.MAX_VALUE;

		return flim;
	}

	public static void qcom_convert(final byte[] src, byte[] pic, final int width, final int _height)
	{
		int pitch = width;

		int height=_height;

		final int tile_w = (width - 1) / TILE_WIDTH + 1;
		final int tile_w_align = (tile_w + 1) & ~1;

		final int tile_h_luma = (height - 1) / TILE_HEIGHT + 1;
		final int tile_h_chroma = (height / 2 - 1) / TILE_HEIGHT + 1;

		int luma_size = tile_w_align * tile_h_luma * TILE_SIZE;


		if((luma_size % TILE_GROUP_SIZE) != 0)
			luma_size = (((luma_size - 1) / TILE_GROUP_SIZE) + 1) * TILE_GROUP_SIZE;

		for(int y = 0; y < tile_h_luma; y++) {
			int row_width = width;
			for(int x = 0; x < tile_w; x++) {
            /* luma source pointer for this tile */
			//	const uint8_t *src_luma  = src
			//			+ tile_pos(x, y,tile_w_align, tile_h_luma) * TILE_SIZE;
			int src_luma=tile_pos(x, y,tile_w_align, tile_h_luma) * TILE_SIZE;

            /* chroma source pointer for this tile */
			//	const uint8_t *src_chroma = src + luma_size
			//			+ tile_pos(x, y/2, tile_w_align, tile_h_chroma) * TILE_SIZE;
			int src_chroma=luma_size+tile_pos(x, y/2, tile_w_align, tile_h_chroma) * TILE_SIZE;
				if ((y & 1)>0)
					src_chroma += TILE_SIZE/2;

            /* account for right columns */
				int tile_width = row_width;
				if (tile_width > TILE_WIDTH)
					tile_width = TILE_WIDTH;

            /* account for bottom rows */
				int tile_height = height;
				if (tile_height > TILE_HEIGHT)
					tile_height = TILE_HEIGHT;

            /* dest luma memory index for this tile */
				int luma_idx = y * TILE_HEIGHT * pitch + x * TILE_WIDTH;

            /* dest chroma memory index for this tile */
            /* XXX: remove divisions */
				int chroma_idx = (luma_idx / pitch) * pitch/2 + (luma_idx % pitch);

				tile_height /= 2; // we copy 2 luma lines at once
				while (tile_height-- > 0) {
					//memcpy(&pic->p[0].p_pixels[luma_idx], src_luma, tile_width);
					System.arraycopy(src, src_luma, pic, luma_idx, tile_width);
					src_luma += TILE_WIDTH;
					luma_idx += pitch;

					//memcpy(&pic->p[0].p_pixels[luma_idx], src_luma, tile_width);
					System.arraycopy(src, src_luma, pic, luma_idx, tile_width);
					src_luma += TILE_WIDTH;
					luma_idx += pitch;

					//memcpy(&pic->p[1].p_pixels[chroma_idx], src_chroma, tile_width);
					System.arraycopy(src, src_chroma, pic, chroma_idx+(width*_height), tile_width);
					src_chroma += TILE_WIDTH;
					chroma_idx += pitch;
				}
				row_width -= TILE_WIDTH;
			}
			height -= TILE_HEIGHT;
		}
	}
}
