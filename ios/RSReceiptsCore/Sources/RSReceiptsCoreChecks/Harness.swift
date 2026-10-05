import Foundation

/// Minimal assertion harness.
///
/// Neither XCTest nor swift-testing ships with the Command Line Tools, so until Xcode is
/// installed the suite is an executable that exits non-zero. Each `check` is one test case;
/// the shape is deliberately close to the JUnit tests it was ported from so the two can be
/// compared side by side. Replace with swift-testing once Xcode exists — the bodies carry over
/// unchanged.
final class Harness {
    private(set) var passed = 0
    private(set) var failures: [String] = []
    private var group = ""

    func section(_ name: String) {
        group = name
        print("\n\u{001B}[1m\(name)\u{001B}[0m")
    }

    func check(_ name: String, _ body: () throws -> Void) {
        do {
            try body()
            passed += 1
            print("  \u{001B}[32m✓\u{001B}[0m \(name)")
        } catch {
            let message = "\(group) › \(name): \(error)"
            failures.append(message)
            print("  \u{001B}[31m✗\u{001B}[0m \(name)\n      \(error)")
        }
    }

    func summarize() -> Never {
        print("\n" + String(repeating: "-", count: 60))
        if failures.isEmpty {
            print("\u{001B}[32m\(passed) checks passed\u{001B}[0m")
            exit(0)
        }
        print("\u{001B}[31m\(failures.count) failed\u{001B}[0m, \(passed) passed")
        for failure in failures { print("  - \(failure)") }
        exit(1)
    }
}

struct CheckFailure: Error, CustomStringConvertible {
    let description: String
}

func expect<T: Equatable>(
    _ actual: T,
    _ expected: T,
    _ note: String = "",
    file: StaticString = #file,
    line: UInt = #line
) throws {
    guard actual == expected else {
        throw CheckFailure(
            description: "expected \(expected), got \(actual)\(note.isEmpty ? "" : " — \(note)") (line \(line))")
    }
}

func expectTrue(_ condition: Bool, _ note: String, line: UInt = #line) throws {
    guard condition else { throw CheckFailure(description: "\(note) (line \(line))") }
}

func expectNil<T>(_ value: T?, _ note: String = "", line: UInt = #line) throws {
    guard value == nil else {
        throw CheckFailure(description: "expected nil, got \(value!) \(note) (line \(line))")
    }
}

func expectThrows<E: Error & Equatable>(
    _ expected: E,
    _ body: () throws -> Void,
    line: UInt = #line
) throws {
    do {
        try body()
        throw CheckFailure(description: "expected to throw \(expected), returned normally (line \(line))")
    } catch let error as E where error == expected {
        return
    } catch let failure as CheckFailure {
        throw failure
    } catch {
        throw CheckFailure(description: "expected \(expected), threw \(error) (line \(line))")
    }
}
