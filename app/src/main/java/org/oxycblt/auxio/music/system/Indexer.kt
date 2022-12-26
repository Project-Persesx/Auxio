/*
 * Copyright (c) 2022 Auxio Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Genre
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicStore
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.music.Sort
import org.oxycblt.auxio.music.extractor.*
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.logE
import org.oxycblt.auxio.util.logW

/**
 * Core music loading state class.
 *
 * This class provides low-level access into the exact state of the music loading process. **This
 * class should not be used in most cases.** It is highly volatile and provides far more information
 * than is usually needed. Use [MusicStore] instead if you do not need to work with the exact music
 * loading state.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class Indexer private constructor() {
    private var lastResponse: Response? = null
    private var indexingState: Indexing? = null
    private var controller: Controller? = null
    private var callback: Callback? = null

    /** Whether music loading is occurring or not. */
    val isIndexing: Boolean
        get() = indexingState != null

    /**
     * Whether this instance has not completed a loading process and is not currently loading music.
     * This often occurs early in an app's lifecycle, and consumers should try to avoid showing any
     * state when this flag is true.
     */
    val isIndeterminate: Boolean
        get() = lastResponse == null && indexingState == null

    /**
     * Register a [Controller] for this instance. This instance will handle any commands to start
     * the music loading process. There can be only one [Controller] at a time. Will invoke all
     * [Callback] methods to initialize the instance with the current state.
     * @param controller The [Controller] to register. Will do nothing if already registered.
     */
    @Synchronized
    fun registerController(controller: Controller) {
        if (BuildConfig.DEBUG && this.controller != null) {
            logW("Controller is already registered")
            return
        }

        // Initialize the controller with the current state.
        val currentState =
            indexingState?.let { State.Indexing(it) } ?: lastResponse?.let { State.Complete(it) }
        controller.onIndexerStateChanged(currentState)
        this.controller = controller
    }

    /**
     * Unregister the [Controller] from this instance, prevent it from recieving any further
     * commands.
     * @param controller The [Controller] to unregister. Must be the current [Controller]. Does
     * nothing if invoked by another [Controller] implementation.
     */
    @Synchronized
    fun unregisterController(controller: Controller) {
        if (BuildConfig.DEBUG && this.controller !== controller) {
            logW("Given controller did not match current controller")
            return
        }

        this.controller = null
    }

    /**
     * Register the [Callback] for this instance. This can be used to receive rapid-fire updates to
     * the current music loading state. There can be only one [Callback] at a time. Will invoke all
     * [Callback] methods to initialize the instance with the current state.
     * @param callback The [Callback] to add.
     */
    @Synchronized
    fun registerCallback(callback: Callback) {
        if (BuildConfig.DEBUG && this.callback != null) {
            logW("Callback is already registered")
            return
        }

        // Initialize the callback with the current state.
        val currentState =
            indexingState?.let { State.Indexing(it) } ?: lastResponse?.let { State.Complete(it) }
        callback.onIndexerStateChanged(currentState)
        this.callback = callback
    }

    /**
     * Unregister a [Callback] from this instance, preventing it from recieving any further updates.
     * @param callback The [Callback] to unregister. Must be the current [Callback]. Does nothing if
     * invoked by another [Callback] implementation.
     * @see Callback
     */
    @Synchronized
    fun unregisterCallback(callback: Callback) {
        if (BuildConfig.DEBUG && this.callback !== callback) {
            logW("Given controller did not match current controller")
            return
        }

        this.callback = null
    }

    /**
     * Start the indexing process. This should be done from in the background from [Controller]'s
     * context after a command has been received to start the process.
     * @param context [Context] required to load music.
     * @param withCache Whether to use the cache or not when loading. If false, the cache will still
     * be written, but no cache entries will be loaded into the new library.
     */
    suspend fun index(context: Context, withCache: Boolean) {
        if (ContextCompat.checkSelfPermission(context, PERMISSION_READ_AUDIO) ==
            PackageManager.PERMISSION_DENIED) {
            // No permissions, signal that we can't do anything.
            emitCompletion(Response.NoPerms)
            return
        }

        val response =
            try {
                val start = System.currentTimeMillis()
                val library = indexImpl(context, withCache)
                if (library != null) {
                    // Successfully loaded a library.
                    logD(
                        "Music indexing completed successfully in " +
                            "${System.currentTimeMillis() - start}ms")
                    Response.Ok(library)
                } else {
                    // Loaded a library, but it contained no music.
                    logE("No music found")
                    Response.NoMusic
                }
            } catch (e: CancellationException) {
                // Got cancelled, propagate upwards to top-level co-routine.
                logD("Loading routine was cancelled")
                throw e
            } catch (e: Exception) {
                // Music loading process failed due to something we have not handled.
                logE("Music indexing failed")
                logE(e.stackTraceToString())
                Response.Err(e)
            }

        emitCompletion(response)
    }

    /**
     * Request that the music library should be reloaded. This should be used by components that do
     * not manage the indexing process in order to signal that the [Controller] should call [index]
     * eventually.
     * @param withCache Whether to use the cache when loading music. Does nothing if there is no
     * [Controller].
     */
    @Synchronized
    fun requestReindex(withCache: Boolean) {
        logD("Requesting reindex")
        controller?.onStartIndexing(withCache)
    }

    /**
     * Reset the current loading state to signal that the instance is not loading. This should be
     * called by [Controller] after it's indexing co-routine was cancelled.
     */
    @Synchronized
    fun reset() {
        logD("Cancelling last job")
        emitIndexing(null)
    }

    /**
     * Internal implementation of the music loading process.
     * @param context [Context] required to load music.
     * @param withCache Whether to use the cache or not when loading. If false, the cache will still
     * be written, but no cache entries will be loaded into the new library.
     * @return A newly-loaded [MusicStore.Library], or null if nothing was loaded.
     */
    private suspend fun indexImpl(context: Context, withCache: Boolean): MusicStore.Library? {
        // Create the chain of extractors. Each extractor builds on the previous and
        // enables version-specific features in order to create the best possible music
        // experience.
        val cacheDatabase =
            if (withCache) {
                ReadWriteCacheExtractor(context)
            } else {
                WriteOnlyCacheExtractor(context)
            }

        val mediaStoreExtractor =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    Api30MediaStoreExtractor(context, cacheDatabase)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    Api29MediaStoreExtractor(context, cacheDatabase)
                else -> Api21MediaStoreExtractor(context, cacheDatabase)
            }

        val metadataExtractor = MetadataExtractor(context, mediaStoreExtractor)

        val songs = buildSongs(metadataExtractor, Settings(context))
        if (songs.isEmpty()) {
            // No songs, nothing else to do.
            return null
        }

        // Build the rest of the music library from the song list. This is much more powerful
        // and reliable compared to using MediaStore to obtain grouping information.
        val buildStart = System.currentTimeMillis()
        val albums = buildAlbums(songs)
        val artists = buildArtists(songs, albums)
        val genres = buildGenres(songs)
        logD("Successfully built library in ${System.currentTimeMillis() - buildStart}ms")

        return MusicStore.Library(songs, albums, artists, genres)
    }

    /**
     * Load a list of [Song]s from the device.
     * @param metadataExtractor The completed [MetadataExtractor] instance to use to load [Song.Raw]
     * instances.
     * @param settings [Settings] required to create [Song] instances.
     * @return A possibly empty list of [Song]s. These [Song]s will be incomplete and must be linked
     * with parent [Album], [Artist], and [Genre] items in order to be usable.
     */
    private suspend fun buildSongs(
        metadataExtractor: MetadataExtractor,
        settings: Settings
    ): List<Song> {
        logD("Starting indexing process")
        val start = System.currentTimeMillis()
        // Start initializing the extractors. Use an indeterminate state, as there is no ETA on
        // how long a media database query will take.
        emitIndexing(Indexing.Indeterminate)
        val total = metadataExtractor.init()
        yield()

        // Note: We use a set here so we can eliminate song duplicates.
        val songs = mutableSetOf<Song>()
        val rawSongs = mutableListOf<Song.Raw>()
        metadataExtractor.parse { rawSong ->
            songs.add(Song(rawSong, settings))
            rawSongs.add(rawSong)

            // Now we can signal a defined progress by showing how many songs we have
            // loaded, and the projected amount of songs we found in the library
            // (obtained by the extractors)
            yield()
            emitIndexing(Indexing.Songs(songs.size, total))
        }

        // Finalize the extractors with the songs we have now loaded. There is no ETA
        // on this process, so go back to an indeterminate state.
        emitIndexing(Indexing.Indeterminate)
        metadataExtractor.finalize(rawSongs)
        logD("Successfully built ${songs.size} songs in ${System.currentTimeMillis() - start}ms")

        // Ensure that sorting order is consistent so that grouping is also consistent.
        // Rolling this into the set is not an option, as songs with the same sort result
        // would be lost.
        return Sort(Sort.Mode.ByName, true).songs(songs)
    }

    /**
     * Build a list of [Album]s from the given [Song]s.
     * @param songs The [Song]s to build [Album]s from. These will be linked with their respective
     * [Album]s when created.
     * @return A non-empty list of [Album]s. These [Album]s will be incomplete and must be linked
     * with parent [Artist] instances in order to be usable.
     */
    private fun buildAlbums(songs: List<Song>): List<Album> {
        // Group songs by their singular raw album, then map the raw instances and their
        // grouped songs to Album values. Album.Raw will handle the actual grouping rules.
        val songsByAlbum = songs.groupBy { it._rawAlbum }
        val albums = songsByAlbum.map { Album(it.key, it.value) }
        logD("Successfully built ${albums.size} albums")
        return albums
    }

    /**
     * Group up [Song]s and [Album]s into [Artist] instances. Both of these items are required as
     * they group into [Artist] instances much differently, with [Song]s being grouped primarily by
     * artist names, and [Album]s being grouped primarily by album artist names.
     * @param songs The [Song]s to build [Artist]s from. One [Song] can result in the creation of
     * one or more [Artist] instances. These will be linked with their respective [Artist]s when
     * created.
     * @param albums The [Album]s to build [Artist]s from. One [Album] can result in the creation of
     * one or more [Artist] instances. These will be linked with their respective [Artist]s when
     * created.
     * @return A non-empty list of [Artist]s. These [Artist]s will consist of the combined groupings
     * of [Song]s and [Album]s.
     */
    private fun buildArtists(songs: List<Song>, albums: List<Album>): List<Artist> {
        // Add every raw artist credited to each Song/Album to the grouping. This way,
        // different multi-artist combinations are not treated as different artists.
        val musicByArtist = mutableMapOf<Artist.Raw, MutableList<Music>>()

        for (song in songs) {
            for (rawArtist in song._rawArtists) {
                musicByArtist.getOrPut(rawArtist) { mutableListOf() }.add(song)
            }
        }

        for (album in albums) {
            for (rawArtist in album._rawArtists) {
                musicByArtist.getOrPut(rawArtist) { mutableListOf() }.add(album)
            }
        }

        // Convert the combined mapping into artist instances.
        val artists = musicByArtist.map { Artist(it.key, it.value) }
        logD("Successfully built ${artists.size} artists")
        return artists
    }

    /**
     * Group up [Song]s into [Genre] instances.
     * @param [songs] The [Song]s to build [Genre]s from. One [Song] can result in the creation of
     * one or more [Genre] instances. These will be linked with their respective [Genre]s when
     * created.
     * @return A non-empty list of [Genre]s.
     */
    private fun buildGenres(songs: List<Song>): List<Genre> {
        // Add every raw genre credited to each Song to the grouping. This way,
        // different multi-genre combinations are not treated as different genres.
        val songsByGenre = mutableMapOf<Genre.Raw, MutableList<Song>>()
        for (song in songs) {
            for (rawGenre in song._rawGenres) {
                songsByGenre.getOrPut(rawGenre) { mutableListOf() }.add(song)
            }
        }

        // Convert the mapping into genre instances.
        val genres = songsByGenre.map { Genre(it.key, it.value) }
        logD("Successfully built ${genres.size} genres")
        return genres
    }

    /**
     * Emit a new [State.Indexing] state. This can be used to signal the current state of the music
     * loading process to external code. Assumes that the callee has already checked if they have
     * not been canceled and thus have the ability to emit a new state.
     * @param indexing The new [Indexing] state to emit, or null if no loading process is occurring.
     */
    @Synchronized
    private fun emitIndexing(indexing: Indexing?) {
        indexingState = indexing
        // If we have canceled the loading process, we want to revert to a previous completion
        // whenever possible to prevent state inconsistency.
        val state =
            indexingState?.let { State.Indexing(it) } ?: lastResponse?.let { State.Complete(it) }
        controller?.onIndexerStateChanged(state)
        callback?.onIndexerStateChanged(state)
    }

    /**
     * Emit a new [State.Complete] state. This can be used to signal the completion of the music
     * loading process to external code. Will check if the callee has not been canceled and thus has
     * the ability to emit a new state
     * @param response The new [Response] to emit, representing the outcome of the music loading
     * process.
     */
    private suspend fun emitCompletion(response: Response) {
        yield()
        // Swap to the Main thread so that downstream callbacks don't crash from being on
        // a background thread. Does not occur in emitIndexing due to efficiency reasons.
        withContext(Dispatchers.Main) {
            synchronized(this) {
                // Do not check for redundancy here, as we actually need to notify a switch
                // from Indexing -> Complete and not Indexing -> Indexing or Complete -> Complete.
                lastResponse = response
                indexingState = null
                // Signal that the music loading process has been completed.
                val state = State.Complete(response)
                controller?.onIndexerStateChanged(state)
                callback?.onIndexerStateChanged(state)
            }
        }
    }

    /** Represents the current state of [Indexer]. */
    sealed class State {
        /**
         * Music loading is ongoing.
         * @param indexing The current music loading progress..
         * @see Indexer.Indexing
         */
        data class Indexing(val indexing: Indexer.Indexing) : State()

        /**
         * Music loading has completed.
         * @param response The outcome of the music loading process.
         * @see Response
         */
        data class Complete(val response: Response) : State()
    }

    /**
     * Represents the current progress of the music loader. Usually encapsulated in a [State].
     * @see State.Indexing
     */
    sealed class Indexing {
        /**
         * Music loading is occurring, but no definite estimate can be put on the current progress.
         */
        object Indeterminate : Indexing()

        /**
         * Music loading has a definite progress.
         * @param current The current amount of songs that have been loaded.
         * @param total The projected total amount of songs that will be loaded.
         */
        class Songs(val current: Int, val total: Int) : Indexing()
    }

    /** Represents the possible outcomes of the music loading process. */
    sealed class Response {
        /**
         * Music load was successful and produced a [MusicStore.Library].
         * @param library The loaded [MusicStore.Library].
         */
        data class Ok(val library: MusicStore.Library) : Response()

        /**
         * Music loading encountered an unexpected error.
         * @param throwable The error thrown.
         */
        data class Err(val throwable: Throwable) : Response()

        /** Music loading occurred, but resulted in no music. */
        object NoMusic : Response()

        /** Music loading could not occur due to a lack of storage permissions. */
        object NoPerms : Response()
    }

    /**
     * A callback for rapid-fire changes in the music loading state.
     *
     * This is only useful for code that absolutely must show the current loading process.
     * Otherwise, [MusicStore.Callback] is highly recommended due to it's updates only consisting of
     * the [MusicStore.Library].
     */
    interface Callback {
        /**
         * Called when the current state of the Indexer changed.
         *
         * Notes:
         * - Null means that no loading is going on, but no load has completed either.
         * - [State.Complete] may represent a previous load, if the current loading process was
         * canceled for one reason or another.
         */
        fun onIndexerStateChanged(state: State?)
    }

    /**
     * Context that runs the music loading process. Implementations should be capable of running the
     * background for long periods of time without android killing the process.
     */
    interface Controller : Callback {
        /**
         * Called when a new music loading process was requested. Implementations should forward
         * this to [index].
         * @param withCache Whether to use the cache or not when loading. If false, the cache should
         * still be written, but no cache entries will be loaded into the new library.
         * @see index
         */
        fun onStartIndexing(withCache: Boolean)
    }

    companion object {
        @Volatile private var INSTANCE: Indexer? = null

        /**
         * A version-compatible identifier for the read external storage permission required by the
         * system to load audio. TODO: Move elsewhere.
         */
        val PERMISSION_READ_AUDIO =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // READ_EXTERNAL_STORAGE was superseded by READ_MEDIA_AUDIO in Android 13
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        /**
         * Get a singleton instance.
         * @return The (possibly newly-created) singleton instance.
         */
        fun getInstance(): Indexer {
            val currentInstance = INSTANCE
            if (currentInstance != null) {
                return currentInstance
            }

            synchronized(this) {
                val newInstance = Indexer()
                INSTANCE = newInstance
                return newInstance
            }
        }
    }
}
