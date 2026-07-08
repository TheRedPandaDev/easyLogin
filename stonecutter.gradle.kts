plugins {
    id("dev.kikugie.stonecutter")
}
stonecutter active "1.21.1"

stonecutter registerChiseled tasks.register("chiseledBuild", stonecutter.chiseled) { 
    group = "project"
    ofTask("build")
}
