package com.vibe.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackRef::class, MatchEntity::class, ListeningEntity::class,
        FollowedArtist::class, ArtistRelease::class],
    version = 4,
    exportSchema = false,
)
abstract class VibeDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun matches(): MatchDao
    abstract fun stats(): StatsDao
    abstract fun artists(): ArtistDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN artistId TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN albumId TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listening` (`trackId` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                        "`ms` INTEGER NOT NULL, PRIMARY KEY(`trackId`, `day`))"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `followed_artists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`artworkUrl` TEXT, `followedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_releases` (`albumId` TEXT NOT NULL, `artistId` TEXT NOT NULL, " +
                        "`artistName` TEXT NOT NULL, `title` TEXT NOT NULL, `subtitle` TEXT, `artworkUrl` TEXT, " +
                        "`year` INTEGER NOT NULL, `discoveredAt` INTEGER NOT NULL, `isNew` INTEGER NOT NULL, PRIMARY KEY(`albumId`))"
                )
            }
        }

        fun create(context: Context): VibeDatabase =
            Room.databaseBuilder(context, VibeDatabase::class.java, "vibe.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
