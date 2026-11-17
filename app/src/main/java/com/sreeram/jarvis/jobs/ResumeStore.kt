package com.sreeram.jarvis.jobs

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

// The resume lives only on the phone, in Buddy's private storage. Its text is read here; nothing is uploaded by this class.
object ResumeStore {
    private fun file(c: Context) = File(c.filesDir, "resume.txt")

    fun hasResume(c: Context) = file(c).exists() && file(c).length() > 0
    fun text(c: Context): String = if (hasResume(c)) file(c).readText() else ""
    fun clear(c: Context) { file(c).delete() }

    // Reads a PDF or plain-text file the user picked. Returns the number of characters read, or throws with a plain reason.
    fun import(c: Context, uri: Uri): Int {
        val name = c.contentResolver.getType(uri).orEmpty()
        val text = c.contentResolver.openInputStream(uri)?.use { input ->
            if (name.contains("pdf") || uri.toString().lowercase().endsWith(".pdf")) {
                PDFBoxResourceLoader.init(c.applicationContext)
                PDDocument.load(input).use { PDFTextStripper().getText(it) }
            } else input.readBytes().toString(Charsets.UTF_8)
        } ?: error("Could not open that file")
        val clean = text.replace('\u0000', ' ').trim()
        if (clean.length < 200) error("That file has almost no text. A scanned or image-only resume cannot be read, so use a text PDF.")
        file(c).writeText(clean)
        return clean.length
    }

    fun skills(c: Context): Set<String> = JobMatcher.resumeSkills(text(c), JobVocabulary.skills)
}

// Skills worth looking for in a resume. A job's own "must have" list is what decides a match, so this only needs the common names.
object JobVocabulary {
    val skills = listOf(
        "java", "kotlin", "python", "javascript", "typescript", "c#", "c++", "go", "rust", "php", "ruby", "swift", "scala", "sql", "nosql",
        "html", "css", "sass", "react", "angular", "vue", "next.js", "node.js", "express", "spring boot", "spring", "hibernate", "django", "flask", "fastapi",
        ".net", "asp.net", "rest api", "graphql", "grpc", "microservices", "system design", "design patterns", "oop", "data structures", "algorithms",
        "mysql", "postgresql", "mongodb", "redis", "elasticsearch", "oracle", "dynamodb", "kafka", "rabbitmq",
        "aws", "azure", "gcp", "docker", "kubernetes", "terraform", "jenkins", "ci/cd", "git", "linux", "ansible",
        "android", "ios", "flutter", "react native", "jetpack compose",
        "machine learning", "deep learning", "nlp", "pandas", "numpy", "tensorflow", "pytorch", "spark", "hadoop", "airflow", "power bi", "tableau",
        "junit", "selenium", "cypress", "jest", "agile", "scrum", "jira", "devops", "sre", "security", "oauth", "jwt",
    )
}
