(ns lichess.puzzle
  (:require [dommy.core :as dommy]
            [ajax.core :as xhr]
            [cljs.core.async :as async :refer [chan <! >! alts! put! close! timeout]])
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:use-macros [dommy.macros :only [sel sel1]]))

(defn log! [& args] (.log js/console (apply pr-str args)))
(defn log-obj! [obj] (.log js/console obj))

(def static-domain (str "http://" (clojure.string/replace (.-domain js/document) #"^\w+" "static")))
(def puzzle-elem (sel1 "#puzzle"))
(def chessboard-elem (sel1 "#chessboard"))
(def prev-elem (sel1 [puzzle-elem :.prev]))
(def next-elem (sel1 [puzzle-elem :.next]))
(def initial-fen (dommy/attr chessboard-elem "data-fen"))
(def initial-move (dommy/attr chessboard-elem "data-move"))
(def post-url (dommy/attr chessboard-elem "data-post-url"))
(def lines (js->clj (js/JSON.parse (dommy/attr chessboard-elem "data-lines"))))
(def drop-chan (chan))
(def animation-delay 300)
(def chess (new js/Chess initial-fen))
(def started-at (new js/Date))

(defn seconds-since-started [] (.round js/Math (/ 1000 (- (.getTime (new js/Date)) (.getTime started-at)))))

(defn playing? [] (dommy/has-class? puzzle-elem "playing"))

(defn apply-move
  ([orig, dest] (.move chess (clj->js {:from orig :to dest})))
  ([move] (let [[a, b, c, d] (seq move)] (apply-move (str a b) (str c d)))))

(defn color-move! [move]
  (let [[a b c d] (seq move) [orig dest] [(str a b) (str c d)]]
    (doseq [s (sel [chessboard-elem :.last])] (dommy/remove-class! s :last))
    (let [squares (clojure.string/join ", " (map #(str ".square-" %) [orig dest]))]
      (doseq [s (sel squares)] (dommy/add-class! s :last)))))

(defn await-in [ch value duration] (js/setTimeout #(put! ch value) duration) ch)

(defn on-drop! [orig, dest]
  (if (and (playing?) (apply-move orig dest)) (put! drop-chan (str orig dest)) "snapback"))

(def chessboard
  (new js/ChessBoard "chessboard"
       (clj->js {:position initial-fen
                 :orientation (dommy/attr chessboard-elem "data-color")
                 :draggable true
                 :dropOffBoard "snapback"
                 :sparePieces false
                 :pieceTheme (str static-domain "/assets/images/chessboard/{piece}.png")
                 :moveSpeed animation-delay
                 :onDrop on-drop!})))

(defn set-position! [fen] (.position chessboard fen))

(defn try-move [progress move]
  (let [new-progress (conj progress move)
        new-lines (get-in lines new-progress)]
    (if new-lines [new-progress new-lines] "fail")))

(defn ai-play! [branch]
  (let [ch (chan) move (first (first branch))]
    (when-let [valid (apply-move move)]
      (color-move! move)
      (go
        (set-position! (.fen chess))
        (await-in ch move (+ 50 animation-delay))))
    ch))

(defn set-status! [status] (dommy/set-attr! puzzle-elem :class status))

(defn handler [response]
  (.log js/console (str response)))

(defn error-handler [{:keys [status status-text]}]
  (.log js/console (str "something bad happened: " status " " status-text)))

(defn post-attempt! [retries win]
  (xhr/ajax-request post-url :post
                    {:params {:win win
                              :hints 0
                              :retries 0
                              :time (seconds-since-started)}
                     ; :handler (fn [] (.reload js/location))
                     :format xhr/raw-format}))

(defn win! [retries]
  (set-status! "win")
  (post-attempt! retries 1))

(defn fail! [retries]
  (set-status! "playing fail")
  (post-attempt! retries 0))

(defn play-loop []
  (go
    (<! (timeout 1000))
    (apply-move initial-move)
    (set-position! (.fen chess))
    (color-move! initial-move)
    (set-status! "playing")
    (loop [progress []
           fen (.fen chess)
           retries 0
           failed false]
      (let [move (<! drop-chan)
            new-progress (conj progress move)
            new-lines (get-in lines new-progress)]
        (case new-lines
          "retry" (do
                    (set-status! "playing retry")
                    (<! (timeout animation-delay))
                    (.load chess fen)
                    (set-position! fen)
                    (recur progress fen (+ 1 retries) failed))
          nil (do
                (when (not failed) (fail! retries))
                (<! (timeout animation-delay))
                (.load chess fen)
                (set-position! fen)
                (recur progress fen retries true))
          (do
            (set-status! "playing")
            (color-move! move)
            (set-position! (.fen chess))
            (<! (timeout (+ animation-delay 50)))
            (if (= new-lines "win")
              (win! retries)
              (let [aim (<! (ai-play! new-lines))]
                (if (= (get new-lines aim) "win")
                  (win! retries)
                  (recur (conj new-progress aim) (.fen chess) retries failed))))))))))

; (defn replay-loop []
;   (go
;       (loop [

(play-loop)