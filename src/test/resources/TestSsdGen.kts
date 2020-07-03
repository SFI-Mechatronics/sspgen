import no.ntnu.ihb.ssp.cli.Kind
import no.ntnu.ihb.ssp.cli.ssp

ssp("TestSsdGen") {

    ssd("A simple CLI test") {

        author = "John Doe"
        description = "A simple description"

        system("Test") {

            description = "An even simpler description"

            elements {
                component("FMU1", "resources/FMU1.fmu") {
                    connectors {
                        realConnector("output", Kind.output) {
                            unit("m/s")
                        }
                        realConnector("input", Kind.input)
                        integerConnector("counter", Kind.output)
                    }
                    parameterbindings {
                        parameterSet("initalValues") {
                            real("input", 2.0)
                            integer("counter", 99)
                        }
                    }
                    annotations {
                        annotation("no.ntnu.ihb.ssp.MyAnnotation") {
                            """
                                <TestElement/>
                            """.trimIndent()
                        }
                    }
                }
                component("FMU2", "resources/FMU2.fmu") {
                    connectors {
                        realConnector("input", Kind.input)
                        realConnector("output", Kind.output)
                    }
                }
            }

            connections {
                "FMU2.output" to "FMU1.input"
                ("FMU1.output" to "FMU2.input").linearTransformation(factor = 1.5)
            }

        }

        defaultExperiment(startTime = 1.0)

    }

}
