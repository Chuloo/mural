import XCTest

final class MuralUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 {
            let footer = app.buttons["onboarding-continue"].frame
            if element.isHittable && element.frame.minY >= 110 && element.frame.maxY < footer.minY - 16 { return }
            app.swipeUp()
        }
        XCTAssertTrue(element.isHittable)
    }

    private func checkNewOnboarding(id: String, greeting: String) {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        let choice = app.buttons["onboarding-language-\(id)"]
        XCTAssertTrue(choice.waitForExistence(timeout: 10))
        reveal(choice, in: app)
        choice.tap()
        XCTAssertTrue(choice.isSelected)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Language selection - \(id)"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, greeting)
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Hi!")
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        if id == "zh" {
            XCTAssertEqual(app.staticTexts["pinyin-reading"].label, "nǐhǎo！")
            app.buttons["pinyin-toggle"].tap()
            XCTAssertFalse(app.staticTexts["pinyin-reading"].exists)
            app.buttons["pinyin-toggle"].tap()
            XCTAssertTrue(app.staticTexts["pinyin-reading"].exists)
        }
    }

    func testTagalogOnboarding() { checkNewOnboarding(id: "tl", greeting: "Kumusta!") }

    func testGermanOnboarding() { checkNewOnboarding(id: "de", greeting: "Hallo!") }
    func testItalianOnboarding() { checkNewOnboarding(id: "it", greeting: "Ciao!") }
    func testBrazilianPortugueseOnboarding() { checkNewOnboarding(id: "pt", greeting: "Olá!") }
    func testMandarinOnboardingWithOptionalPinyin() { checkNewOnboarding(id: "zh", greeting: "你好！") }

    func testMandarinSelectionAtLargestAccessibilityTextSize() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let choice = app.buttons["onboarding-language-zh"]
        XCTAssertTrue(choice.waitForExistence(timeout: 10))
        reveal(choice, in: app)
        choice.tap()
        XCTAssertTrue(choice.isSelected)
        XCTAssertTrue(app.buttons["onboarding-continue"].isHittable)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        let privacy = app.descendants(matching: .any).matching(identifier: "onboarding-privacy-policy").firstMatch
        reveal(privacy, in: app)
        XCTAssertTrue(app.staticTexts["onboarding-ai-consent"].exists)
        XCTAssertTrue(app.buttons["onboarding-continue"].isHittable)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Mandarin onboarding - largest accessibility text"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "你好！")
    }

    func testNewLanguageSettingsThemesWordsAndReturnToNorwegian() {
        let app = launch()
        for (selection, name, greeting, theme) in [
            ("German · Germany", "German", "Hallo!", "Ein Kaffee?"),
            ("Italian · Italy", "Italian", "Ciao!", "Un caffè?"),
            ("Portuguese · Brazil", "Portuguese", "Olá!", "Um cafezinho?"),
            ("Mandarin Chinese · Mainland China", "Mandarin Chinese", "你好！", "喝杯咖啡？"),
            ("Tagalog (Filipino) · Philippines", "Tagalog (Filipino)", "Kumusta!", "Kape tayo?")
        ] {
            app.buttons["Settings"].tap()
            app.buttons["learning-language-picker"].tap()
            app.buttons[selection].tap()
            app.buttons["Done"].tap()
            XCTAssertEqual(app.staticTexts["target-caption"].label, greeting)
            app.tabBars.buttons["Themes"].tap()
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", theme)).firstMatch.exists)
            app.tabBars.buttons["Words"].tap()
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ==[c] %@", "Little by little · \(name)")).firstMatch.exists)
            app.tabBars.buttons["Talk"].tap()
        }
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Norwegian · Bokmål"].tap()
        app.buttons["Done"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertFalse(app.buttons["pinyin-toggle"].exists)
    }

    func testSimplifiedChineseMeaningsAreAvailableInOnboarding() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-continue"].waitForExistence(timeout: 10))
        app.buttons["onboarding-continue"].tap()
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["Chinese (Simplified)"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "你好！")
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["meaning-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "你好！")
    }

    func testMandarinTranscriptRetainsSourceTextAndPinyinAfterReset() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--ended-conversation", "--preview-language=zh"]
        app.launch()
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "我喜欢喝咖啡。")
        XCTAssertEqual(app.staticTexts.matching(identifier: "pinyin-reading").firstMatch.label, "wǒ xǐhuān hē kāfēi。")
        app.buttons["Conversation transcript"].tap()
        XCTAssertTrue(app.staticTexts["我喜欢喝咖啡。"].exists)
        XCTAssertEqual(app.staticTexts.matching(identifier: "pinyin-reading").firstMatch.label, "wǒ xǐhuān hē kāfēi。")
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Mandarin transcript and pinyin"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["Done"].tap()
        app.buttons["new-conversation"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "你好！")
        app.tabBars.buttons["Words"].tap()
        app.buttons["Past conversations"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "喝杯咖啡？")).firstMatch.exists)
    }

    func testTagalogOnboardingAtLargestAccessibilitySizePreservesSubtitleChoice() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let choice = app.buttons["onboarding-language-tl"]
        XCTAssertTrue(choice.waitForExistence(timeout: 10))
        reveal(choice, in: app)
        choice.tap()
        XCTAssertTrue(choice.isSelected)
        XCTAssertEqual(choice.label, "Tagalog (Filipino) · Philippines")
        let viewport = app.scrollViews.firstMatch.frame
        let continueButton = app.buttons["onboarding-continue"]
        XCTAssertGreaterThan(choice.frame.height, 0)
        XCTAssertTrue(viewport.contains(choice.frame), "The entire Tagalog card must fit inside the visible scroll area")
        XCTAssertTrue(app.frame.contains(continueButton.frame), "Continue must fit inside the screen")
        XCTAssertLessThan(choice.frame.maxY, continueButton.frame.minY)
        XCTAssertTrue(continueButton.isHittable)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Tagalog onboarding - largest accessibility text"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["onboarding-continue"].tap()
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["French"].tap()
        app.buttons["onboarding-back"].tap()
        reveal(choice, in: app)
        XCTAssertTrue(choice.isSelected)
        app.buttons["onboarding-continue"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "Salut !")
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Kumusta!")
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Salut !")
        XCTAssertFalse(app.buttons["pinyin-toggle"].exists)
    }

    func testTagalogTranscriptAndMeaningSurviveResetAndLanguageSwitch() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--ended-conversation", "--preview-language=tl"]
        app.launch()
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Gusto ko ng kape.")
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "I like coffee.")
        XCTAssertFalse(app.buttons["pinyin-toggle"].exists)
        app.buttons["Conversation transcript"].tap()
        XCTAssertTrue(app.staticTexts["Gusto ko ng kape."].exists)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Tagalog transcript and English meaning"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["Done"].tap()
        if app.buttons["new-conversation"].exists { app.buttons["new-conversation"].tap() }
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Kumusta!")
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Spanish · Spain"].tap()
        app.buttons["Done"].tap()
        app.tabBars.buttons["Words"].tap()
        app.buttons["Past conversations"].tap()
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Kape tayo?")).firstMatch.exists)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.tabBars.buttons["Talk"].tap()
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Tagalog (Filipino) · Philippines"].tap()
        app.buttons["Done"].tap()
        app.tabBars.buttons["Words"].tap()
        app.buttons["Past conversations"].tap()
        let saved = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Kape tayo?")).firstMatch
        XCTAssertTrue(saved.exists)
        saved.tap()
        XCTAssertTrue(app.staticTexts["Gusto ko ng kape."].exists)
    }

    func testTagalogLanguageSwitchIsDisabledDuringConversation() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--active-conversation", "--preview-language=tl"]
        app.launch()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Gusto ko ng kape.")
        app.buttons["Settings"].tap()
        XCTAssertFalse(app.buttons["learning-language-picker"].isEnabled)
        XCTAssertTrue(app.staticTexts["End this conversation to switch languages. Each language keeps its own words and progress."].exists)
    }

    func testTagalogSelectionPersistsAcrossNormalRelaunch() {
        let app = XCUIApplication()
        addTeardownBlock {
            app.terminate()
            app.launch()
            XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 10))
            app.buttons["Settings"].tap()
            app.buttons["learning-language-picker"].tap()
            app.buttons["Norwegian · Bokmål"].tap()
            app.buttons["Done"].tap()
            app.terminate()
            app.launch()
            XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 10))
            XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
            app.terminate()
        }
        app.launch()
        if app.buttons["onboarding-continue"].waitForExistence(timeout: 5) {
            let choice = app.buttons["onboarding-language-tl"]
            reveal(choice, in: app)
            choice.tap()
            app.buttons["onboarding-continue"].tap()
            app.buttons["onboarding-continue"].tap()
        }
        XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 10))
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Tagalog (Filipino) · Philippines"].tap()
        app.buttons["Done"].tap()
        app.terminate()
        app.launch()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Kumusta!")
        XCTAssertFalse(app.buttons["onboarding-continue"].exists)
        app.buttons["Settings"].tap()
        XCTAssertTrue(app.buttons["learning-language-picker"].label.contains("Tagalog (Filipino)"))
    }

    private func launch(ended: Bool = false) -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["--preview"] + (ended ? ["--ended-conversation"] : [])
        app.launch(); return app
    }
    func testGreetingAndMeaningToggle() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        app.buttons["Hide meaning subtitles"].tap()
        XCTAssertFalse(app.staticTexts["meaning-caption"].exists)
        app.buttons["Show meaning subtitles"].tap()
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Hi!")
    }
    func testThemeSurvivesNavigationToWords() {
        let app = launch()
        app.tabBars.buttons["Themes"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "A coffee?")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["A coffee?"].exists)
        app.tabBars.buttons["Words"].tap()
        XCTAssertTrue(app.staticTexts["Your words."].exists)
        app.tabBars.buttons["Talk"].tap()
        XCTAssertTrue(app.staticTexts["A coffee?"].exists)
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
    }
    func testSettingsOfferSecureKeyEntryAndBackups() {
        let app = launch()
        app.buttons["Settings"].tap()
        if app.buttons["managed-account-settings"].exists {
            app.buttons["managed-account-settings"].tap()
            XCTAssertTrue(app.staticTexts["managed-sign-in-agreement"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["managed-google-sign-in"].isHittable || app.buttons["managed-apple-sign-in"].isHittable)
            XCTAssertFalse(app.staticTexts["managedAccountMessage"].exists)
            XCTAssertFalse(app.buttons["Buy credits"].exists)
            let accountScreen = XCTAttachment(screenshot: app.screenshot())
            accountScreen.name = "Configured account signup"; accountScreen.lifetime = .keepAlways; add(accountScreen)
            app.navigationBars["Account"].buttons.element(boundBy: 0).tap()
        }
        XCTAssertFalse(app.secureTextFields["api-key"].exists)
        app.buttons["advanced-api-key"].tap()
        if !app.secureTextFields["api-key"].exists { app.swipeUp() }
        XCTAssertTrue(app.secureTextFields["api-key"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Done"].exists)
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["start-conversation"].exists)
    }

    func testSettingsKeepLicensesInNoticesWithoutTransportDetails() {
        let app = launch()
        app.buttons["Settings"].tap()
        for _ in 0..<6 {
            if app.buttons["Open-source notices"].isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(app.buttons["Open-source notices"].isHittable)
        XCTAssertFalse(app.staticTexts["WebRTC distribution by stasel, BSD 3-Clause. WebRTC includes third-party open-source components."].exists)
        XCTAssertFalse(app.links["WebRTC licenses"].exists)
        app.buttons["Open-source notices"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Google WebRTC")).firstMatch.waitForExistence(timeout: 5))
    }

    func testExistingUserCanDeclineThenAcceptAIConsentWithoutRepeatingOnboarding() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-existing-user"]
        app.launch()
        XCTAssertTrue(app.buttons["start-conversation"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["onboarding-language-fr"].exists)
        app.buttons["start-conversation"].tap()
        XCTAssertTrue(app.staticTexts["ai-consent-title"].waitForExistence(timeout: 5))
        app.buttons["ai-consent-decline"].tap()
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        app.buttons["start-conversation"].tap()
        XCTAssertTrue(app.staticTexts["ai-consent-title"].waitForExistence(timeout: 5))
        app.buttons["ai-consent-agree"].tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        app.buttons["start-conversation"].tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["ai-consent-title"].exists)
        XCTAssertFalse(app.buttons["onboarding-language-fr"].exists)
    }

    func testOnboardingChoosesLearningAndSubtitleLanguagesWithoutAnAccount() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-language-fr"].waitForExistence(timeout: 10))
        let languageScreen = XCTAttachment(screenshot: app.screenshot())
        languageScreen.name = "Onboarding - language"; languageScreen.lifetime = .keepAlways; add(languageScreen)
        app.buttons["onboarding-language-fr"].tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["onboarding-ai-consent"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "onboarding-privacy-policy").firstMatch.exists)
        XCTAssertEqual(app.buttons["onboarding-continue"].label, "Agree and continue")
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["Spanish"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "¡Hola!")
        let meaningScreen = XCTAttachment(screenshot: app.screenshot())
        meaningScreen.name = "Onboarding - meanings and consent"; meaningScreen.lifetime = .keepAlways; add(meaningScreen)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Salut !")
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "¡Hola!")
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        XCTAssertFalse(app.secureTextFields["api-key"].exists)
    }

    func testEnglishOnboardingOffersOtherMeaningsAndPreservesAnExplicitChoice() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-language-en"].waitForExistence(timeout: 10))
        app.buttons["onboarding-language-en"].tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        XCTAssertNotEqual(app.staticTexts["onboarding-meaning-example"].label, "Hi!")
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["Spanish"].tap()
        app.buttons["onboarding-back"].tap()
        app.buttons["onboarding-language-fr"].tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "¡Hola!")
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "¡Hola!")
    }

    func testSettingsCanSwitchToEnglishAndFrench() {
        let app = launch()
        for (selection, greeting) in [("English · International", "Hi!"), ("French · France", "Salut !")] {
            app.buttons["Settings"].tap()
            app.buttons["learning-language-picker"].tap()
            app.buttons[selection].tap()
            app.buttons["Done"].tap()
            XCTAssertEqual(app.staticTexts["target-caption"].label, greeting)
        }
    }
    func testLanguageSwitchUpdatesGreetingThemesAndWords() {
        let app = launch()
        app.tabBars.buttons["Themes"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "A coffee?")).firstMatch.tap()
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Spanish · Spain"].tap()
        app.buttons["Done"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "¡Hola!")
        XCTAssertTrue(app.staticTexts["A little everyday Spanish"].exists)
        app.tabBars.buttons["Themes"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Un café")).firstMatch.exists)
        app.tabBars.buttons["Words"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ==[c] %@", "Little by little · Spanish")).firstMatch.exists)
        app.tabBars.buttons["Talk"].tap()
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Norwegian · Bokmål"].tap()
        app.buttons["Done"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
    }

    func testMeaningLabelWorksAfterEndingAndManualResetKeepsHistory() {
        let app = launch(ended: true)
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["new-conversation"].isHittable)
        XCTAssertTrue(app.buttons["start-conversation"].isHittable)
        XCTAssertTrue(app.buttons["Conversation transcript"].isHittable)
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "I like coffee.")
        app.buttons["Hide meaning subtitles"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.93)).tap()
        XCTAssertFalse(app.staticTexts["meaning-caption"].exists)
        app.buttons["Show meaning subtitles"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.93)).tap()
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "I like coffee.")
        app.buttons["new-conversation"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        XCTAssertFalse(app.staticTexts["A coffee?"].exists)
        app.tabBars.buttons["Words"].tap()
        app.buttons["Past conversations"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "A coffee?")).firstMatch.exists)
    }

    func testEndedConversationAutomaticallyReturnsToGreeting() {
        let app = launch(ended: true)
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Jeg liker kaffe.")
        let ready = NSPredicate(format: "label == %@", "Ready when you are")
        expectation(for: ready, evaluatedWith: app.staticTexts["conversation-status"])
        waitForExpectations(timeout: 18)
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Hi!")
        XCTAssertFalse(app.buttons["new-conversation"].exists)
    }

    func testOpenTranscriptRemainsReadableAfterAutomaticReset() {
        let app = launch(ended: true)
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
        app.buttons["Conversation transcript"].tap()
        XCTAssertTrue(app.staticTexts["I like coffee."].exists)
        let delay = expectation(description: "Allow the 15-second reset to finish")
        DispatchQueue.main.asyncAfter(deadline: .now() + 16) { delay.fulfill() }
        waitForExpectations(timeout: 18)
        XCTAssertTrue(app.staticTexts["Jeg liker kaffe."].exists)
        XCTAssertTrue(app.staticTexts["I like coffee."].exists)
        app.buttons["Done"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
    }
}
