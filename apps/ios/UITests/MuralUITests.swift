import XCTest

final class MuralUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func confirmAdult(in app: XCUIApplication) {
        let control = app.switches["onboarding-adult-confirmation"]
        XCTAssertTrue(control.exists)
        if app.launchArguments.contains("UICTContentSizeCategoryAccessibilityXXXL") { reveal(control, in: app) }
        control.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        XCTAssertEqual(control.value as? String, "1")
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 {
            let footer = app.buttons["onboarding-continue"].frame
            if element.isHittable && element.frame.minY >= 110 && element.frame.maxY < footer.minY - 16 { return }
            if element.frame.maxY <= 110 { app.swipeDown() }
            else { app.swipeUp() }
        }
        XCTAssertTrue(element.isHittable)
    }

    private func checkNewOnboarding(id: String, greeting: String) {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        let picker = app.buttons["onboarding-language-picker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        reveal(picker, in: app)
        picker.tap()
        let choice = app.buttons["onboarding-language-\(id)"]
        XCTAssertTrue(choice.waitForExistence(timeout: 5))
        choice.tap()
        XCTAssertTrue(picker.exists)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Language selection - \(id)"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        confirmAdult(in: app)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, greeting)
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Hi!")
        XCTAssertFalse(app.staticTexts["microphone-status"].exists)
        XCTAssertEqual(app.buttons["start-conversation"].label, "Start conversation")
        if id == "zh" {
            XCTAssertEqual(app.staticTexts["pinyin-reading"].label, "nǐhǎo！")
            app.buttons["pinyin-toggle"].tap()
            XCTAssertFalse(app.staticTexts["pinyin-reading"].exists)
            app.buttons["pinyin-toggle"].tap()
            XCTAssertTrue(app.staticTexts["pinyin-reading"].exists)
        }
    }

    func testGermanOnboarding() { checkNewOnboarding(id: "de", greeting: "Hallo!") }
    func testItalianOnboarding() { checkNewOnboarding(id: "it", greeting: "Ciao!") }
    func testBrazilianPortugueseOnboarding() { checkNewOnboarding(id: "pt", greeting: "Olá!") }
    func testSerbianOnboarding() { checkNewOnboarding(id: "sr", greeting: "Zdravo!") }
    func testMandarinOnboardingWithOptionalPinyin() { checkNewOnboarding(id: "zh", greeting: "你好！") }

    func testMandarinSelectionAtLargestAccessibilityTextSize() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let picker = app.buttons["onboarding-language-picker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        reveal(picker, in: app)
        picker.tap()
        let choice = app.buttons["onboarding-language-zh"]
        for _ in 0..<6 {
            if choice.isHittable { break }
            app.collectionViews.firstMatch.swipeUp()
        }
        XCTAssertTrue(choice.waitForExistence(timeout: 5))
        XCTAssertTrue(choice.isHittable)
        choice.tap()
        XCTAssertTrue(picker.exists)
        XCTAssertTrue(app.buttons["onboarding-continue"].isHittable)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        let privacy = app.descendants(matching: .any).matching(identifier: "onboarding-privacy-policy").firstMatch
        reveal(privacy, in: app)
        XCTAssertTrue(app.staticTexts["onboarding-ai-consent"].exists)
        XCTAssertTrue(app.buttons["onboarding-continue"].isHittable)
        confirmAdult(in: app)
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
            ("Mandarin Chinese · Mainland China", "Mandarin Chinese", "你好！", "喝杯咖啡？")
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
        confirmAdult(in: app)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["meaning-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "你好！")
    }

    func testMandarinTranscriptRetainsSourceTextAndPinyinAfterReset() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--ended-conversation", "--preview-language=zh"]
        app.launch()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["new-conversation"].exists)
        XCTAssertEqual(app.staticTexts["target-caption"].label, "我喜欢喝咖啡。")
        XCTAssertEqual(app.staticTexts.matching(identifier: "pinyin-reading").firstMatch.label, "wǒ xǐhuān hē kāfēi。")
        app.buttons["Conversation transcript"].tap()
        XCTAssertTrue(app.staticTexts["我喜欢喝咖啡。"].exists)
        XCTAssertEqual(app.staticTexts.matching(identifier: "pinyin-reading").firstMatch.label, "wǒ xǐhuān hē kāfēi。")
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Mandarin transcript and pinyin"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["Done"].tap()
        let greeting = NSPredicate(format: "label == %@", "你好！")
        expectation(for: greeting, evaluatedWith: app.staticTexts["target-caption"])
        waitForExpectations(timeout: 18)
        XCTAssertEqual(app.staticTexts["target-caption"].label, "你好！")
        app.tabBars.buttons["Words"].tap()
        app.buttons["Past conversations"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "喝杯咖啡？")).firstMatch.exists)
    }

    func testTypedReplyFailureKeepsDraftAndRetrySavesOnlyOneReply() {
        for largeText in [false, true] {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--test-typed-retry"] + (largeText ? ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] : [])
        app.launch()
        XCTAssertTrue(app.buttons["Type instead"].waitForExistence(timeout: 10))
        app.buttons["Type instead"].tap()
        let field = app.textViews["typed-reply-input"].exists ? app.textViews["typed-reply-input"] : app.textFields["typed-reply-input"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("Quiero un cafe.")
        app.buttons["typed-reply-send"].tap()
        XCTAssertTrue(app.staticTexts["typed-reply-error"].waitForExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "Quiero un cafe.")
        XCTAssertTrue(app.buttons["typed-reply-send"].isHittable)
        if app.keyboards.firstMatch.exists {
            XCTAssertLessThanOrEqual(app.buttons["typed-reply-send"].frame.maxY, app.keyboards.firstMatch.frame.minY + 1)
        }
        let failure = XCTAttachment(screenshot: app.screenshot())
        failure.name = largeText ? "Large text typed reply failure" : "Typed reply failure preserves draft"; failure.lifetime = .keepAlways; add(failure)
        app.buttons["typed-reply-send"].tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 5))
        app.buttons["End conversation"].tap()
        XCTAssertTrue(app.buttons["Conversation transcript"].waitForExistence(timeout: 8))
        app.buttons["Conversation transcript"].tap()
        XCTAssertEqual(app.staticTexts.matching(identifier: "transcript-user-passage").count, 1)
        }
    }

    func testEndNoticeKeepsReasonButClearsStaleHelp() {
        for inactivity in [true, false] {
            let app = XCUIApplication()
            app.launchArguments = ["--preview", "--ended-conversation", "--test-end-notice"] + (inactivity ? ["--test-inactivity"] : [])
            app.launch()
            let expected = inactivity ? "Mural ended this quiet session to avoid running up usage." : "Conversation saved. Final voice usage is unconfirmed."
            XCTAssertTrue(app.staticTexts[expected].waitForExistence(timeout: 10))
            XCTAssertFalse(app.staticTexts["Mural will make that a little simpler."].exists)
        }
    }

    private func launch(ended: Bool = false) -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["--preview"] + (ended ? ["--ended-conversation"] : [])
        app.launch(); return app
    }
    func testGreetingAndMeaningToggle() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertFalse(app.staticTexts["microphone-status"].exists)
        XCTAssertFalse(app.staticTexts["talk-guest-minutes"].exists)
        XCTAssertFalse(app.staticTexts["Reply in whichever language comes to you."].exists)
        XCTAssertEqual(app.buttons["start-conversation"].label, "Start conversation")
        let target = app.staticTexts["target-caption"].frame
        let meaning = app.staticTexts["meaning-caption"].frame
        XCTAssertGreaterThanOrEqual(meaning.minY, target.maxY)
        XCTAssertLessThan(meaning.minY - target.maxY, 40)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Quiet idle Talk"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["Hide meaning subtitles"].tap()
        XCTAssertFalse(app.staticTexts["meaning-caption"].exists)
        app.buttons["Show meaning subtitles"].tap()
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Hi!")
    }
    func testTalkControlsStayPutWhenConversationStartsAndMeaningFails() {
        let idle = launch()
        let idleMicY = idle.buttons["start-conversation"].frame.midY
        let idleMeaningY = idle.buttons["Hide meaning subtitles"].frame.midY
        let idleTranscriptY = idle.buttons["Conversation transcript"].frame.midY
        idle.terminate()

        let active = XCUIApplication()
        active.launchArguments = ["--preview", "--screenshot=conversation"]
        active.launch()
        XCTAssertTrue(active.buttons["Type instead"].waitForExistence(timeout: 10))
        XCTAssertEqual(active.buttons["start-conversation"].frame.midY, idleMicY, accuracy: 2)
        XCTAssertEqual(active.buttons["Hide meaning subtitles"].frame.midY, idleMeaningY, accuracy: 2)
        XCTAssertEqual(active.buttons["End conversation"].frame.midY, idleTranscriptY, accuracy: 2)
        active.terminate()

        let failed = XCUIApplication()
        failed.launchArguments = ["--preview", "--preview-meaning-error"]
        failed.launch()
        XCTAssertTrue(failed.staticTexts["meaning-error"].waitForExistence(timeout: 10))
        XCTAssertTrue(failed.staticTexts["meaning-error"].label.contains("Meaning isn’t available yet"))
        XCTAssertTrue(failed.buttons["Try meaning again"].exists)
        XCTAssertEqual(failed.buttons["start-conversation"].frame.midY, idleMicY, accuracy: 2)
        let screen = XCTAttachment(screenshot: failed.screenshot())
        screen.name = "Meaning failure keeps Talk controls fixed"; screen.lifetime = .keepAlways; add(screen)
        failed.terminate()

        let limited = XCUIApplication()
        limited.launchArguments = ["--preview", "--preview-meaning-limit"]
        limited.launch()
        XCTAssertTrue(limited.staticTexts["meaning-error"].waitForExistence(timeout: 10))
        XCTAssertTrue(limited.staticTexts["meaning-error"].label.contains("limit for extra meanings"))
        XCTAssertFalse(limited.buttons["Try meaning again"].exists)
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
        XCTAssertFalse(app.staticTexts["microphone-status"].exists)
    }
    func testSettingsOfferSecureKeyEntryAndBackups() {
        let app = launch()
        XCTAssertFalse(app.staticTexts["talk-guest-minutes"].exists)
        app.buttons["Settings"].tap()
        XCTAssertFalse(app.staticTexts["Start talking"].exists)
        if app.buttons["managed-account-settings"].exists {
            app.buttons["managed-account-settings"].tap()
            XCTAssertTrue(app.staticTexts["managed-sign-in-agreement"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["managed-google-sign-in"].isHittable || app.buttons["managed-apple-sign-in"].isHittable)
            XCTAssertFalse(app.staticTexts["managedAccountMessage"].exists)
            XCTAssertFalse(app.buttons["Buy credits"].exists)
            XCTAssertFalse(app.staticTexts["managed-account-minutes"].exists)
            let accountScreen = XCTAttachment(screenshot: app.screenshot())
            accountScreen.name = "Configured account signup"; accountScreen.lifetime = .keepAlways; add(accountScreen)
            app.navigationBars["Account"].buttons.element(boundBy: 0).tap()
        }
        XCTAssertFalse(app.secureTextFields["api-key"].exists)
        app.buttons["settings-conversation-access"].tap()
        app.buttons["My API key"].tap()
        if !app.secureTextFields["api-key"].exists { app.swipeUp() }
        XCTAssertTrue(app.secureTextFields["api-key"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Done"].exists)
        app.navigationBars["API key"].buttons["Done"].tap()
        XCTAssertFalse(app.buttons["advanced-api-key"].exists)
        XCTAssertTrue(app.staticTexts["Mural minutes"].exists)
        app.buttons["Learning backup & data"].tap()
        XCTAssertTrue(app.buttons["Export learning backup"].exists)
        app.navigationBars["Learning data"].buttons.element(boundBy: 0).tap()
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["start-conversation"].exists)
    }

    func testPersonalKeyKeepsAccountFreeOfMuralMetersAndUsesQuietActions() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-key", "--preview-apple", "--preview-purchases"]
        app.launch()
        XCTAssertFalse(app.staticTexts["talk-guest-minutes"].exists)
        app.buttons["Settings"].tap()
        app.buttons["managed-account-settings"].tap()
        XCTAssertTrue(app.staticTexts["managed-account-email"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["managed-account-minutes"].exists)
        XCTAssertFalse(app.buttons["account-add-minutes"].exists)
        XCTAssertTrue(app.buttons["account-purchase-history"].exists)
        XCTAssertTrue(app.buttons["managed-account-sign-out"].isHittable)
        XCTAssertTrue(app.buttons["Delete account…"].isHittable)
        app.buttons["managed-account-sign-out"].tap()
        XCTAssertTrue(app.staticTexts["Sign out on all devices?"].waitForExistence(timeout: 5))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.8)).tap()
        XCTAssertTrue(app.staticTexts["managed-account-email"].exists)
    }

    func testMemberMuralBalanceUsesVerifiedFixture() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-member"]
        app.launch()
        XCTAssertFalse(app.staticTexts["talk-guest-minutes"].exists)
        app.buttons["Settings"].tap()
        app.buttons["managed-account-settings"].tap()
        XCTAssertTrue(app.staticTexts["managed-account-minutes"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["managed-account-minutes"].label, "8 min 54 sec")
        XCTAssertFalse(app.buttons["settings-conversation-access"].exists)
    }

    private func purchasePreview(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-paid-member", "--preview-purchases", "-AppleLocale", "en_US", "-AppleLanguages", "(en)"] + extra
        app.launch()
        app.buttons["Settings"].tap()
        app.buttons["managed-account-settings"].tap()
        let add = app.buttons["account-add-minutes"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        if !add.isHittable { app.swipeUp() }
        add.tap()
        XCTAssertTrue(app.buttons["minute-continue"].waitForExistence(timeout: 5))
        return app
    }
    func testPurchaseQuantityFloorsAfterAggregationAndKeepsCheckoutStationary() {
        let app = purchasePreview()
        let next = app.buttons["minute-continue"]
        XCTAssertEqual(next.label, "Continue · $7.00")
        let position = next.frame
        let stepper = app.steppers["minute-quantity"]
        stepper.buttons["minute-quantity-Increment"].tap()
        XCTAssertTrue(app.staticTexts["minute-total"].label.contains("About 73 min"))
        XCTAssertEqual(next.label, "Continue · $14.00")
        XCTAssertEqual(next.frame.minY, position.minY, accuracy: 1)
        app.buttons["minute-offer-large"].tap()
        XCTAssertEqual(next.label, "Continue · $40.00")
        XCTAssertTrue(app.staticTexts["minute-total"].label.contains("About 232 min"))
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Apple packs - quantity two"; screen.lifetime = .keepAlways; add(screen)
        next.tap()
        XCTAssertTrue(app.staticTexts["Purchase preview · no payment was made."].exists)
    }
    func testPurchaseControlsRemainReachableAtLargestTextSize() {
        let app = purchasePreview(["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        XCTAssertTrue(app.buttons["minute-continue"].isHittable)
        XCTAssertTrue(app.steppers["minute-quantity"].buttons["minute-quantity-Increment"].isHittable)
        app.swipeUp()
        XCTAssertTrue(app.buttons["minute-continue"].isHittable)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Apple packs - largest text"; screen.lifetime = .keepAlways; add(screen)
    }
    func testPendingPurchaseDisablesAnotherCheckout() {
        let app = purchasePreview(["--preview-purchase-pending"])
        XCTAssertFalse(app.buttons["minute-continue"].isEnabled)
        XCTAssertFalse(app.steppers["minute-quantity"].isEnabled)
        XCTAssertFalse(app.buttons["minute-offer-small"].isEnabled)
        XCTAssertTrue(app.buttons["minute-check-purchases"].isEnabled)
    }
    func testPurchaseHistoryKeepsRefundsAccountBoundAndUsesMinutesOnly() {
        let app = purchasePreview([])
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["account-purchase-history"].tap()
        XCTAssertTrue(app.staticTexts["Recent Apple purchases"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Refund recorded"].exists)
        XCTAssertTrue(app.staticTexts["Quantity · 2"].exists)
        XCTAssertFalse(app.buttons["Request a refund"].firstMatch.isEnabled)
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "USD")).firstMatch.exists)
    }
    func testPurchaseAccessibilityAudit() throws {
        let app = purchasePreview([])
        try app.performAccessibilityAudit(for: [.contrast, .hitRegion, .sufficientElementDescription, .textClipped, .trait])
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["account-purchase-history"].tap()
        XCTAssertTrue(app.staticTexts["Recent Apple purchases"].waitForExistence(timeout: 5))
        try app.performAccessibilityAudit(for: [.contrast, .hitRegion, .sufficientElementDescription, .textClipped, .trait])
    }
    func testFreeBoundaryPreservesConversationAndWaitsForSettlement() { checkFreeBoundary(largeText: false) }
    func testFreeBoundaryAtLargestTextSize() { checkFreeBoundary(largeText: true) }
    func testSettledInsufficientContinuationOpensAccountWithoutStartingACall() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-member", "--ended-conversation", "--preview-free-boundary", "--preview-continuation-insufficient"]
        app.launch()
        let add = app.buttons["continuation-add-minutes"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        XCTAssertTrue(add.isEnabled)
        XCTAssertFalse(app.buttons["continue-conversation"].exists)
        XCTAssertTrue(app.staticTexts["Your conversation is saved. You don’t have enough minutes to continue. Add minutes in Account when you’re ready."].exists)
        app.buttons["Done"].tap()
        XCTAssertFalse(add.exists)
        XCTAssertFalse(app.staticTexts["Your conversation is saved. You don’t have enough minutes to continue. Add minutes in Account when you’re ready."].exists)
        app.buttons["Start conversation"].tap()
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        XCTAssertTrue(app.navigationBars["Account"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["End conversation"].exists)
    }
    private func checkFreeBoundary(largeText: Bool) {
        for pending in [true, false] {
            let app = XCUIApplication()
            app.launchArguments = ["--preview", "--ended-conversation", "--preview-free-boundary"] + (pending ? ["--preview-settlement-pending"] : []) +
                (largeText ? ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] : [])
            app.launch()
            let next = app.buttons["continue-conversation"]
            XCTAssertTrue(next.waitForExistence(timeout: 5))
            XCTAssertEqual(next.isEnabled, !pending)
            if largeText { for _ in 0..<5 { if app.buttons["new-conversation"].isHittable { break }; app.swipeUp() } }
            XCTAssertTrue(app.buttons["new-conversation"].isHittable)
            if !pending { XCTAssertTrue(next.isHittable) }
            let screen = XCTAttachment(screenshot: app.screenshot())
            screen.name = (pending ? "Free boundary - updating minutes" : "Free boundary - continue conversation") + (largeText ? " - largest text" : "")
            screen.lifetime = .keepAlways; add(screen)
            app.buttons["Done"].tap()
            XCTAssertFalse(app.buttons["continue-conversation"].exists)
            XCTAssertFalse(app.buttons["new-conversation"].exists)
            XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Your free minutes have ended")).firstMatch.exists)
            XCTAssertTrue(app.staticTexts["target-caption"].exists)
            let home = XCTAttachment(screenshot: app.screenshot()); home.name = "Minimal Talk after dismissing continuation"
            home.lifetime = .keepAlways; add(home)
            let microphone = app.buttons["Start conversation"]
            if largeText { for _ in 0..<5 { if microphone.isHittable { break }; app.swipeUp() } }
            microphone.tap()
            XCTAssertTrue(next.waitForExistence(timeout: 5))
            app.terminate()
        }
    }

    func testMixedMuralMinutesFloorTheCombinedEstimate() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-paid-member"]
        app.launch()
        app.buttons["Settings"].tap()
        app.buttons["managed-account-settings"].tap()
        let balance = app.staticTexts["managed-account-minutes"]
        XCTAssertTrue(balance.waitForExistence(timeout: 5))
        XCTAssertEqual(balance.label, "About 41 min")
        XCTAssertTrue(app.staticTexts["estimated conversation time remaining"].exists)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Account with mixed Mural minutes"; screen.lifetime = .keepAlways; add(screen)
    }

    func testPaidOnlyMuralMinutesStayAvailable() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-paid-only"]
        app.launch()
        app.buttons["Settings"].tap()
        app.buttons["managed-account-settings"].tap()
        let balance = app.staticTexts["managed-account-minutes"]
        XCTAssertTrue(balance.waitForExistence(timeout: 5))
        XCTAssertEqual(balance.label, "About 36 min")
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Account with paid Mural minutes"; screen.lifetime = .keepAlways; add(screen)
    }

    func testPaidOnlyBalanceCanSwitchFromPersonalKey() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-key", "--preview-paid-only"]
        app.launch()
        app.buttons["Settings"].tap()
        let access = app.buttons["settings-conversation-access"]
        XCTAssertTrue(access.waitForExistence(timeout: 5))
        access.tap()
        app.buttons["Mural minutes"].tap()
        let balance = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "About 36 min")).firstMatch
        XCTAssertTrue(balance.waitForExistence(timeout: 5))
        app.buttons["Use Mural minutes"].tap()
        XCTAssertTrue(access.label.contains("Mural minutes"))
    }

    func testReservedPaidMinutesAreNotShownAsSpendable() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-paid-reserved"]
        app.launch()
        app.buttons["Settings"].tap()
        app.buttons["managed-account-settings"].tap()
        let balance = app.staticTexts["managed-account-minutes"]
        XCTAssertTrue(balance.waitForExistence(timeout: 5))
        XCTAssertEqual(balance.label, "Updating your minutes…")
        XCTAssertTrue(app.staticTexts["Some minutes are in use"].exists)
    }

    func testProviderFailureCanSwitchFromKeyDetailsToMural() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-key", "--preview-provider-quota"]
        app.launch()
        let review = app.alerts.buttons["Review in Advanced"]
        XCTAssertTrue(review.waitForExistence(timeout: 5))
        review.tap()
        let key = app.buttons["advanced-api-key"]
        XCTAssertTrue(key.waitForExistence(timeout: 5))
        key.tap()
        app.buttons["Use Mural minutes"].tap()
        XCTAssertTrue(app.navigationBars["Conversation access"].waitForExistence(timeout: 5))
        let confirm = app.buttons["Use Mural minutes"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        let balance = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "8 min 54 sec")).firstMatch
        XCTAssertTrue(balance.waitForExistence(timeout: 5))
        confirm.tap()
        let access = app.buttons["settings-conversation-access"]
        XCTAssertTrue(access.waitForExistence(timeout: 5))
        XCTAssertTrue(access.label.contains("Mural minutes"))
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["start-conversation"].exists)
    }

    func testPersonalKeySwitchToMuralRequiresFreshConfirmation() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-key"]
        app.launch()
        app.buttons["Settings"].tap()
        let access = app.buttons["settings-conversation-access"]
        XCTAssertTrue(access.waitForExistence(timeout: 5))
        XCTAssertTrue(access.label.contains("My API key"))

        access.tap()
        app.buttons["Mural minutes"].tap()
        XCTAssertTrue(app.buttons["Use Mural minutes"].waitForExistence(timeout: 5))
        app.buttons["Cancel"].tap()
        XCTAssertTrue(access.label.contains("My API key"))

        access.tap()
        app.buttons["Mural minutes"].tap()
        XCTAssertTrue(app.buttons["Use Mural minutes"].waitForExistence(timeout: 5))
        app.buttons["Use Mural minutes"].tap()
        XCTAssertTrue(access.label.contains("Mural minutes"))
    }

    func testSettingsKeepLicensesInNoticesWithoutTransportDetails() {
        let app = launch()
        app.buttons["Settings"].tap()
        for _ in 0..<6 {
            if app.buttons["About Mural"].isHittable { break }
            app.swipeUp()
        }
        app.buttons["About Mural"].tap()
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
        XCTAssertFalse(app.staticTexts["microphone-status"].exists)
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
        let languagePicker = app.buttons["onboarding-language-picker"]
        XCTAssertTrue(languagePicker.waitForExistence(timeout: 10))
        let languageScreen = XCTAttachment(screenshot: app.screenshot())
        languageScreen.name = "Onboarding - language"; languageScreen.lifetime = .keepAlways; add(languageScreen)
        languagePicker.tap()
        app.buttons["onboarding-language-fr"].tap()
        XCTAssertTrue(languagePicker.label.contains("French · France"))
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Mural speaks French")).firstMatch.exists)
        XCTAssertTrue(app.staticTexts["onboarding-ai-consent"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "onboarding-privacy-policy").firstMatch.exists)
        XCTAssertEqual(app.buttons["onboarding-continue"].label, "Agree and continue")
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["Spanish"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "¡Hola!")
        let meaningScreen = XCTAttachment(screenshot: app.screenshot())
        meaningScreen.name = "Onboarding - meanings and consent"; meaningScreen.lifetime = .keepAlways; add(meaningScreen)
        confirmAdult(in: app)
        XCTAssertTrue(app.buttons["onboarding-continue"].isEnabled)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["onboarding-continue"].exists)
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Salut !")
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "¡Hola!")
        XCTAssertFalse(app.staticTexts["microphone-status"].exists)
        XCTAssertFalse(app.secureTextFields["api-key"].exists)
    }

    func testEnglishOnboardingOffersOtherMeaningsAndPreservesAnExplicitChoice() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        let languagePicker = app.buttons["onboarding-language-picker"]
        XCTAssertTrue(languagePicker.waitForExistence(timeout: 10))
        languagePicker.tap()
        app.buttons["onboarding-language-en"].tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        XCTAssertNotEqual(app.staticTexts["onboarding-meaning-example"].label, "Hi!")
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["Spanish"].tap()
        app.buttons["onboarding-back"].tap()
        languagePicker.tap()
        app.buttons["onboarding-language-fr"].tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "¡Hola!")
        confirmAdult(in: app)
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

    func testMeaningLabelWorksAfterEndingAndAutomaticResetKeepsHistory() {
        let app = launch(ended: true)
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["new-conversation"].exists)
        XCTAssertTrue(app.buttons["start-conversation"].isHittable)
        XCTAssertTrue(app.buttons["Conversation transcript"].isHittable)
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "I like coffee.")
        app.buttons["Hide meaning subtitles"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.93)).tap()
        XCTAssertFalse(app.staticTexts["meaning-caption"].exists)
        app.buttons["Show meaning subtitles"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.93)).tap()
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "I like coffee.")
        let greeting = NSPredicate(format: "label == %@", "Hei!")
        expectation(for: greeting, evaluatedWith: app.staticTexts["target-caption"])
        waitForExpectations(timeout: 18)
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertFalse(app.staticTexts["microphone-status"].exists)
        XCTAssertFalse(app.staticTexts["A coffee?"].exists)
        app.tabBars.buttons["Words"].tap()
        app.buttons["Past conversations"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "A coffee?")).firstMatch.exists)
    }

    func testEndedConversationAutomaticallyReturnsToGreeting() {
        let app = launch(ended: true)
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["new-conversation"].exists)
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
        XCTAssertTrue(app.buttons["Conversation transcript"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["new-conversation"].exists)
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
    func testNetworkRecoveryLifecycleThroughRealTransport() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--verify-network-recovery"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Network recovery lifecycle passed"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["Network recovery lifecycle failed"].exists)
    }
    func testInactivityCountdownRemainsReadableAtLargestTextSize() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-inactivity", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let warning = app.staticTexts["conversation-status"]
        XCTAssertTrue(warning.waitForExistence(timeout: 10))
        XCTAssertTrue(warning.isHittable)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Inactivity countdown - largest text"; screen.lifetime = .keepAlways; add(screen)
    }
    func testQuietSessionClosesAndPreservesItsExplanation() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-inactivity-timer"]
        app.launch()
        let ended = app.staticTexts["Mural ended this quiet session to avoid running up usage."]
        XCTAssertTrue(ended.waitForExistence(timeout: 16))
        XCTAssertFalse(app.staticTexts["microphone-status"].exists)
    }
    func testProviderQuotaShowsUsefulAdviceAndSafeSupportReference() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-provider-quota"]
        app.launch()
        let message = app.alerts.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "OpenAI billing needs attention")).firstMatch
        XCTAssertTrue(message.waitForExistence(timeout: 10))
        XCTAssertTrue(message.label.contains("req_support_fixture"))
        XCTAssertFalse(message.label.contains("private"))
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Provider quota error"; screen.lifetime = .keepAlways; add(screen)
        app.alerts.buttons["OK"].tap()
        XCTAssertFalse(app.alerts.firstMatch.exists)
    }

    func testHostedMinutesExhaustedOpensKeyRecovery() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-hosted-no-minutes"]
        app.launch()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 10))
        XCTAssertTrue(alert.staticTexts["No Mural minutes are available for a new conversation. Check Account or use your own API key."].exists)
        XCTAssertTrue(alert.buttons["Check minutes"].exists)
        alert.buttons["Use my API key"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["settings-conversation-access"].exists)
    }

    func testHostedSignInRequiredOpensAccount() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-hosted-sign-in"]
        app.launch()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 10))
        alert.buttons["Sign in"].tap()
        XCTAssertTrue(app.staticTexts["Welcome to Mural"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["managed-apple-sign-in"].exists)
    }

}
