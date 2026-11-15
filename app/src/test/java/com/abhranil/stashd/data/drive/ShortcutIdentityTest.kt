package com.abhranil.stashd.data.drive

import com.abhranil.stashd.testing.FakeTokenProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** P2-of-review #2: a folder holding file X and a shortcut to X must list two distinct entries. */
class ShortcutIdentityTest {
    private val server = MockWebServer()
    private lateinit var repo: RealDriveRepository

    @Before fun setUp() {
        server.start()
        repo = RealDriveRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, FakeTokenProvider("t"), DriveApiConfig(server.url("/drive/v3/")))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun fileAndShortcutToItKeepSeparateIdsAndNames() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[
            {"id":"fileX","name":"Song.flac","mimeType":"audio/flac","size":"10"},
            {"id":"short1","name":"Shortcut to Song","mimeType":"application/vnd.google-apps.shortcut",
             "shortcutDetails":{"targetId":"fileX","targetMimeType":"audio/flac"}}]}"""))
        val items = repo.listPage("parent1234", null).items
        assertEquals(2, items.size)
        assertEquals(listOf("fileX", "short1"), items.map { it.id })
        assertEquals(2, items.map { it.id }.toSet().size)
        assertEquals(listOf("Song.flac", "Shortcut to Song"), items.map { it.name })
    }

    @Test fun shortcutToFolderExposesTargetForNavigation() = runTest {
        server.enqueue(MockResponse().setBody("""{"files":[
            {"id":"albumF","name":"Album","mimeType":"application/vnd.google-apps.folder"},
            {"id":"short2","name":"Shortcut to Album","mimeType":"application/vnd.google-apps.shortcut",
             "shortcutDetails":{"targetId":"albumF","targetMimeType":"application/vnd.google-apps.folder","targetResourceKey":"0-tk"}}]}"""))
        val (folder, shortcut) = repo.listPage("parent1234", null).items
        assertEquals(true, folder.isFolder && shortcut.isFolder)
        assertEquals("short2", shortcut.id)
        assertEquals("Shortcut to Album", shortcut.name)
        assertEquals(ShortcutTarget("albumF", DriveItem.MIME_FOLDER, "0-tk"), shortcut.shortcut)
        assertEquals("albumF" to "0-tk", shortcut.targetId to shortcut.targetResourceKey)
        assertEquals("albumF" to null, folder.targetId to folder.targetResourceKey)
    }

    @Test fun resolvingAShortcutLinkBrowsesTheTarget() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"shortLink1","name":"Shortcut","mimeType":"application/vnd.google-apps.shortcut",
            "shortcutDetails":{"targetId":"albumF","targetMimeType":"application/vnd.google-apps.folder","targetResourceKey":"0-tk"}}"""))
        server.enqueue(MockResponse().setBody("""{"id":"albumF","name":"Album","mimeType":"application/vnd.google-apps.folder"}"""))
        val item = repo.resolve(DriveLink("shortLink1", null, true))
        assertEquals("albumF", item.id)
        assertEquals("0-tk", item.resourceKey)
        server.takeRequest()
        assertEquals("albumF/0-tk", server.takeRequest().getHeader(ResourceKeys.HEADER))
    }
}
