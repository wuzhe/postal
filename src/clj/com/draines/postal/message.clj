(ns com.draines.postal.message
  (:use [clojure.test :only [run-tests deftest is]]
        [com.draines.postal.date :only [make-date]])
  (:import [java.util Properties UUID]
           [javax.mail Session Message$RecipientType]
           [javax.mail.internet MimeMessage InternetAddress
            AddressException]))

(declare make-jmessage)

(defn recipients [msg]
  (let [jmsg (make-jmessage msg)]
    (map str (.getAllRecipients jmsg))))

(defn sender [msg]
  (or (:sender msg) (:from msg)))

(defn make-address [addr & [personal]]
  (try
    (if personal
      (InternetAddress. addr personal "UTF-8")
      (InternetAddress. addr))
    (catch Exception _)))

(defn message->str [msg]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (let [jmsg (if (instance? MimeMessage msg) msg (make-jmessage msg))]
      (.writeTo jmsg out)
      (str out))))

(defn add-recipient! [jmsg rtype addr]
  (if-let [addr (make-address addr)]
    (doto jmsg
      (.addRecipient rtype addr))
    jmsg))

(defn add-recipients! [jmsg rtype addrs]
  (when addrs
    (if (string? addrs)
      (add-recipient! jmsg rtype addrs)
      (doseq [addr addrs]
        (add-recipient! jmsg rtype addr))))
  jmsg)

(defn add-multipart! [jmsg parts]
  (let [mp (javax.mail.internet.MimeMultipart.)
        fileize (fn [x]
                  (if (instance? java.io.File x) x (java.io.File. x)))]
    (doseq [part parts]
      (condp (fn [test type] (some #(= % type) test)) (:type part)
        [:inline :attachment]
        (.addBodyPart mp
                      (doto (javax.mail.internet.MimeBodyPart.)
                        (.attachFile (fileize (:content part)))
                        (.setDisposition (name (:type part)))))
        (.addBodyPart mp
                      (doto (javax.mail.internet.MimeBodyPart.)
                        (.setContent (:content part) (:type part))))))
    (.setContent jmsg mp)))

(defn add-extra! [jmsg msgrest]
  (doseq [[n v] msgrest]
    (.addHeader jmsg (if (keyword? n) (name n) n) v))
  jmsg)

(defn add-body! [jmsg body]
  (if (string? body)
    (doto jmsg (.setText body "UTF-8"))
    (doto jmsg (add-multipart! body))))

(defn drop-keys [m ks]
  (select-keys m
               (clojure.set/difference (set (keys m)) (set ks))))

(defn make-jmessage
  ([msg]
     (let [{:keys [sender from]} msg
           {:keys [host port]} (meta msg)
           props (doto (java.util.Properties.)
                   (.put "mail.smtp.host" (or host "not.provided"))
                   (.put "mail.smtp.port" (or port "25"))
                   (.put "mail.smtp.from" (or sender from)))
           session (or (:session (meta msg)) (Session/getInstance props))]
       (make-jmessage msg session)))
  ([msg session]
     (let [standard [:from :to :cc :bcc :date :subject :body]
           jmsg (MimeMessage. session)]
       (doto jmsg
         (add-recipients! Message$RecipientType/TO (:to msg))
         (add-recipients! Message$RecipientType/CC (:cc msg))
         (add-recipients! Message$RecipientType/BCC (:bcc msg))
         (.setFrom (apply make-address (:from msg)))
         (.setSubject (:subject msg) "UTF-8")
         (.setSentDate (or (:date msg) (make-date)))
         (add-extra! (drop-keys msg standard))
         (add-body! (:body msg))))))

(defn make-fixture [from to & {:keys [tag]}]
  (let [uuid (str (UUID/randomUUID))
        tag (or tag "[POSTAL]")]
    {:from from
     :to to
     :subject (format "%s Test -- %s" tag uuid)
     :body (format "Test %s" uuid)}))

(deftest test-simple
  (let [m {:from "fee@bar.dom"
           :to "Foo Bar <foo@bar.dom>"
           :cc ["baz@bar.dom" "Quux <quux@bar.dom>"]
           :date (java.util.Date.)
           :subject "Test"
           :body "Test!"}]
    (is (= "Subject: Test" (re-find #"Subject: Test" (message->str m))))
    (is (re-find #"Cc: baz@bar.dom, Quux <quux@bar.dom>" (message->str m)))))

(deftest test-multipart
  (let [m {:from "foo@bar.dom"
           :to "baz@bar.dom"
           :subject "Test"
           :body [{:type "text/html"
                   :content "<b>some html</b>"}]}]
    (is (= "multipart/mixed" (re-find #"multipart/mixed" (message->str m))))
    (is (= "Content-Type: text/html"
           (re-find #"Content-Type: text/html" (message->str m))))
    (is (= "some html" (re-find #"some html" (message->str m))))))

(deftest test-inline
  (let [f (doto (java.io.File/createTempFile "_postal-" ".txt"))
        _ (doto (java.io.PrintWriter. f)
            (.println "tempfile contents") (.close))
        m {:from "foo@bar.dom"
           :to "baz@bar.dom"
           :subject "Test"
           :body [{:type :inline
                   :content f}]}]
    (is (= "tempfile" (re-find #"tempfile" (message->str m))))
    (.delete f)))

(deftest test-attachment
  (let [f1 (doto (java.io.File/createTempFile "_postal-" ".txt"))
        _ (doto (java.io.PrintWriter. f1)
            (.println "tempfile contents") (.close))
        f2 "/etc/resolv.conf"
        m {:from "foo@bar.dom"
           :to "baz@bar.dom"
           :subject "Test"
           :body [{:type :attachment
                   :content f1}
                  {:type :attachment
                   :content f2}]}]
    (is (= "tempfile" (re-find #"tempfile" (message->str m))))
    (.delete f1)))

(deftest test-fixture
  (let [from "foo@bar.dom"
        to "baz@bar.dom"
        tag "[TEST]"]
    (is (re-find #"^\[TEST" (:subject (make-fixture from to :tag tag))))))

(deftest test-extra-headers
  (let [m {:from "foo@bar.dom"
           :to "baz@bar.dom"
           :subject "Test"
           :User-Agent "Lorem Ipsum"
           :body "Foo!"}]
    (is (re-find #"User-Agent: Lorem Ipsum" (message->str m)))))

(deftest test-bad-addrs
  (let [m {:from "foo @bar.dom"
           :to "badddz@@@bar.dom"
           :subject "Test"
           :body "Bad recipient!"}]
    (is (not (re-find #"badddz" (message->str m))))
    (is (not (re-find #"foo @bar" (message->str m))))))
